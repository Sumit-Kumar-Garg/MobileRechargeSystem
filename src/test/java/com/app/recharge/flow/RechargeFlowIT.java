package com.app.recharge.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.recharge.common.JobLease;
import com.app.recharge.notification.NotificationService;
import com.app.recharge.order.OrderEventPayload;
import com.app.recharge.order.OrderStore;
import com.app.recharge.order.OrderTransitions;
import com.app.recharge.payment.PaymentRepository;
import com.app.recharge.payment.PaymentService;
import com.app.recharge.payment.PspGateway;
import com.app.recharge.provisioning.ProvisioningService;
import com.app.recharge.reliability.ReconciliationJob;
import com.app.recharge.reliability.ReconciliationProperties;
import com.app.recharge.support.AbstractIntegrationTest;
import com.app.recharge.support.TestJwt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** End-to-end through the real HTTP layer and a real Postgres: the flows that matter most for money. */
class RechargeFlowIT extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ProvisioningService provisioning;
    @Autowired NotificationService notificationService;
    @Autowired OrderStore orders;
    @Autowired PaymentRepository payments;
    @Autowired PaymentService paymentService;
    @Autowired PspGateway psp;
    @Autowired OrderTransitions transitions;
    @Autowired TransactionTemplate tx;
    @Autowired Clock clock;
    @Autowired JobLease lease;

    // ---------------- helpers ----------------

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, UUID user) {
        return b.header("Authorization", TestJwt.bearer(user)).contentType(MediaType.APPLICATION_JSON);
    }

    private String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private UUID linkNumber(UUID user, String msisdn) throws Exception {
        String json = body(mockMvc.perform(auth(post("/v1/numbers"), user)
                .content("{\"msisdn\":\"" + msisdn + "\"}")).andExpect(status().isOk()));
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private UUID planId(String code) throws Exception {
        String json = body(mockMvc.perform(get("/v1/plans").param("market", "DE")).andExpect(status().isOk()));
        List<String> ids = JsonPath.read(json, "$.plans[?(@.code=='" + code + "')].id");
        return UUID.fromString(ids.get(0));
    }

    private ResultActions createOrder(UUID user, UUID numberId, UUID planId, String key) throws Exception {
        return mockMvc.perform(auth(post("/v1/orders"), user)
                .header("Idempotency-Key", key)
                .content("{\"numberId\":\"" + numberId + "\",\"planId\":\"" + planId + "\"}"));
    }

    private UUID newOrder(UUID user, UUID numberId, UUID planId) throws Exception {
        String json = body(createOrder(user, numberId, planId, key()).andExpect(status().isCreated()));
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private ResultActions pay(UUID user, UUID orderId, String token) throws Exception {
        return mockMvc.perform(auth(post("/v1/orders/" + orderId + "/pay"), user)
                .content("{\"paymentToken\":\"" + token + "\"}"));
    }

    private String statusOf(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private void awaitStatus(UUID orderId, String expected) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo(expected));
    }

    private static String key() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ---------------- flows ----------------

    @Test
    void happyPath_endsCompleted_withEventsJourneyAndNotifications() throws Exception {
        UUID user = UUID.randomUUID();
        UUID number = linkNumber(user, "+491701234567");
        UUID plan = planId("S-28");
        String idem = key();

        String created = body(createOrder(user, number, plan, idem).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.amountMinor").value(1000))
                .andExpect(jsonPath("$.msisdnMasked").value("+49 *** ***4567")));
        UUID orderId = UUID.fromString(JsonPath.read(created, "$.id"));

        // replay with the same key returns the same order; a different request with that key is rejected
        createOrder(user, number, plan, idem).andExpect(jsonPath("$.id").value(orderId.toString()));
        createOrder(user, number, planId("M-28"), idem).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        pay(user, orderId, "tok_success").andExpect(status().isOk());
        awaitStatus(orderId, "COMPLETED");

        mockMvc.perform(auth(get("/v1/orders/" + orderId), user))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.validUntil").exists());

        List<String> types = jdbc.queryForList(
                "select type from outbox_events where event_key = ? order by id", String.class, orderId.toString());
        assertThat(types).containsExactly("OrderCreated", "OrderPaid", "OrderCompleted");

        // server journey events travel through the outbox to the telemetry topic (not written in the money path)
        List<String> journey = jdbc.queryForList(
                "select type from outbox_events where topic = 'recharge.client.telemetry.v1' and payload like ?",
                String.class, "%" + orderId + "%");
        assertThat(journey).contains("journey.payment_started", "journey.payment_succeeded", "journey.recharge_completed");

        // notifications: register a device and an email, then feed the OrderCompleted event through the consumer logic
        mockMvc.perform(auth(put("/v1/devices"), user).content("{\"token\":\"fcm_test_token_1234567890abcdef\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(auth(put("/v1/notification-preferences"), user).content(
                        "{\"pushEnabled\":true,\"smsEnabled\":true,\"emailEnabled\":true,\"locale\":\"de-DE\",\"email\":\"user@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("user@example.com"));
        String payload = jdbc.queryForObject(
                "select payload from outbox_events where event_key = ? and type = 'OrderCompleted'",
                String.class, orderId.toString());
        OrderEventPayload event = mapper.readValue(payload, OrderEventPayload.class);

        notificationService.handle(event);
        notificationService.handle(event);          // duplicate delivery from Kafka must not duplicate notifications

        List<String> channels = jdbc.queryForList(
                "select channel || ':' || status from notifications where order_id = ? order by channel", String.class, orderId);
        assertThat(channels).containsExactly("EMAIL:SENT", "PUSH:SENT");
    }

    @Test
    void declinedCard_canBeRetriedWithAnotherCard() throws Exception {
        UUID user = UUID.randomUUID();
        UUID number = linkNumber(user, "+491701234568");
        UUID orderId = newOrder(user, number, planId("S-28"));

        pay(user, orderId, "tok_decline").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.failureCode").value("card_declined"));

        pay(user, orderId, "tok_success").andExpect(status().isOk());
        awaitStatus(orderId, "COMPLETED");

        assertThat(jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, orderId))
                .isEqualTo(2);
    }

    @Test
    void thirdFailedAttemptOnOneOrderIsBlocked() throws Exception {
        UUID user = UUID.randomUUID();
        UUID number = linkNumber(user, "+491701234569");
        UUID orderId = newOrder(user, number, planId("S-28"));

        for (int i = 0; i < 3; i++) {
            pay(user, orderId, "tok_decline").andExpect(status().isOk());
        }
        pay(user, orderId, "tok_success").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PAYMENT_LIMIT_EXCEEDED"));
    }

    @Test
    void pspTimeout_staysPending_thenReconciliationCompletesIt() throws Exception {
        UUID user = UUID.randomUUID();
        UUID number = linkNumber(user, "+491701234570");
        UUID orderId = newOrder(user, number, planId("M-28"));

        pay(user, orderId, "tok_timeout").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));
        // paying again must not charge a second time
        pay(user, orderId, "tok_success").andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));
        assertThat(jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, orderId))
                .isEqualTo(1);

        ReconciliationJob job = new ReconciliationJob(orders, paymentService, psp, transitions, tx, lease,
                ReconciliationProperties.defaults(), clock);
        assertThat(job.reconcilePayments(Duration.ZERO)).isGreaterThanOrEqualTo(1);

        awaitStatus(orderId, "COMPLETED");
    }

    @Test
    void ocsOutage_exhaustsRetries_thenRefundsTheCustomer() throws Exception {
        UUID user = UUID.randomUUID();
        UUID number = linkNumber(user, "+491701230000");      // mock OCS: always unavailable
        UUID orderId = newOrder(user, number, planId("S-28"));

        pay(user, orderId, "tok_success").andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).until(() -> {
            provisioning.provisionOnce(orderId);              // what the worker would do on each tick
            return "REFUNDED".equals(statusOf(orderId));
        });

        assertThat(jdbc.queryForObject("select status from refunds where order_id = ?", String.class, orderId))
                .isEqualTo("SUCCEEDED");
        List<String> types = jdbc.queryForList(
                "select type from outbox_events where event_key = ? order by id", String.class, orderId.toString());
        assertThat(types).contains("OrderRefunded").doesNotContain("OrderCompleted");
        assertThat(jdbc.queryForObject("select provisioning_attempts from orders where id = ?", Integer.class, orderId))
                .isEqualTo(3);
    }

    @Test
    void numberRejectedByTheBillingSystem_isRefundedWithoutPointlessRetries() throws Exception {
        UUID user = UUID.randomUUID();
        UUID number = linkNumber(user, "+491701239999");      // mock OCS: permanently rejected
        UUID orderId = newOrder(user, number, planId("S-28"));

        pay(user, orderId, "tok_success").andExpect(status().isOk());
        awaitStatus(orderId, "REFUNDED");

        assertThat(jdbc.queryForObject("select provisioning_attempts from orders where id = ?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select failure_code from orders where id = ?", String.class, orderId))
                .isEqualTo("number_not_eligible");
    }

    @Test
    void jobLease_isHeldByOneOwnerAndCanBeTakenOverAfterRelease() {
        JobLease other = new JobLease(jdbc);
        String name = "test-lease-" + UUID.randomUUID();

        assertThat(lease.tryAcquire(name, Duration.ofMinutes(1))).isTrue();
        assertThat(lease.tryAcquire(name, Duration.ofMinutes(1))).isTrue();     // the holder can renew
        assertThat(other.tryAcquire(name, Duration.ofMinutes(1))).isFalse();    // nobody else can take it

        lease.release(name);
        assertThat(other.tryAcquire(name, Duration.ofMinutes(1))).isTrue();
        assertThat(lease.tryAcquire(name, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void transientOcsFailure_recoversOnRetry() throws Exception {
        UUID user = UUID.randomUUID();
        UUID number = linkNumber(user, "+491701230001");      // mock OCS: fails twice, then works
        UUID orderId = newOrder(user, number, planId("S-28"));

        pay(user, orderId, "tok_success").andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).until(() -> {
            provisioning.provisionOnce(orderId);
            return "COMPLETED".equals(statusOf(orderId));
        });
    }

    @Test
    void otherUsersCannotSeeOrPayOrOrderOnSomeoneElsesNumber() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID number = linkNumber(owner, "+491701234571");
        UUID plan = planId("S-28");
        UUID orderId = newOrder(owner, number, plan);

        mockMvc.perform(auth(get("/v1/orders/" + orderId), stranger))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        pay(stranger, orderId, "tok_success").andExpect(status().isNotFound());
        createOrder(stranger, number, plan, key()).andExpect(status().isNotFound());
        mockMvc.perform(auth(get("/v1/orders"), stranger)).andExpect(jsonPath("$.length()").value(0));

        assertThat(statusOf(orderId)).isEqualTo("CREATED");
    }

    @Test
    void protectedEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/v1/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/numbers").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void numberLimitAndValidation() throws Exception {
        UUID user = UUID.randomUUID();
        linkNumber(user, "+491701239101");
        linkNumber(user, "+491701239102");
        linkNumber(user, "+491701239103");

        mockMvc.perform(auth(post("/v1/numbers"), user).content("{\"msisdn\":\"+491701239104\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NUMBER_LIMIT_EXCEEDED"));
        // linking an existing number again is idempotent, not an error
        linkNumber(user, "+49 170 1239101");
        mockMvc.perform(auth(post("/v1/numbers"), UUID.randomUUID()).content("{\"msisdn\":\"0170\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
    }

    @Test
    void orderValidation_rejectsBadIdempotencyKeyAndMissingFields() throws Exception {
        UUID user = UUID.randomUUID();
        UUID number = linkNumber(user, "+491701234572");

        createOrder(user, number, planId("S-28"), "short").andExpect(status().isBadRequest());
        mockMvc.perform(auth(post("/v1/orders"), user).header("Idempotency-Key", key()).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void recommendations_respectConsent() throws Exception {
        UUID user = UUID.randomUUID();
        UUID number = linkNumber(user, "+491701234573");
        mockMvc.perform(auth(post("/v1/demo/usage"), user)
                .content("{\"numberId\":\"" + number + "\",\"dataMbUsed\":9500}")).andExpect(status().isNoContent());

        // no consent: non-personal default (middle plan), usage is ignored
        mockMvc.perform(auth(get("/v1/recommendations").param("numberId", number.toString()), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("POPULAR_DEFAULT"))
                .andExpect(jsonPath("$.personalized").value(false))
                .andExpect(jsonPath("$.planCode").value("M-28"));

        mockMvc.perform(auth(put("/v1/consents/personalization"), user).content("{\"granted\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.personalization").value(true))
                .andExpect(jsonPath("$.analytics").value(false));

        mockMvc.perform(auth(get("/v1/recommendations").param("numberId", number.toString()), user))
                .andExpect(jsonPath("$.reason").value("USAGE_FIT"))
                .andExpect(jsonPath("$.personalized").value(true))
                .andExpect(jsonPath("$.planCode").value("L-28"))
                .andExpect(jsonPath("$.cheaperAlternativePlanId").exists());

        assertThat(jdbc.queryForObject("select count(*) from recommendation_log where user_id = ?", Integer.class, user))
                .isEqualTo(1);
    }

    @Test
    void recommendationForSomeoneElsesNumberIsNotFound() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID number = linkNumber(owner, "+491701234574");
        mockMvc.perform(auth(get("/v1/recommendations").param("numberId", number.toString()), UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void telemetry_isConsentGated_andRedactsPii() throws Exception {
        UUID user = UUID.randomUUID();
        String now = java.time.Instant.now().toString();
        UUID journeyEvent = UUID.randomUUID();
        UUID callEvent = UUID.randomUUID();
        String batch = """
                {"sessionId":"session-abcdef12","appVersion":"1.0.0","events":[
                  {"id":"%s","type":"journey","name":"number_entered","occurredAt":"%s",
                   "props":{"note":"call me +491701234567","ok":1}},
                  {"id":"%s","type":"network_call","name":"api_call","occurredAt":"%s",
                   "props":{"endpoint":"/v1/plans","networkType":"wifi","outcome":"success","durationMs":120,"serverDurationMs":30}}
                ]}""".formatted(journeyEvent, now, callEvent, now);

        // without analytics consent only the operational event is accepted
        mockMvc.perform(auth(post("/v1/telemetry/batch"), user).content(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1)).andExpect(jsonPath("$.dropped").value(1));
        assertThat(jdbc.queryForObject("select count(*) from journey_events where id = ?", Integer.class, journeyEvent))
                .isZero();

        mockMvc.perform(auth(put("/v1/consents/analytics"), user).content("{\"granted\":true}")).andExpect(status().isOk());
        mockMvc.perform(auth(post("/v1/telemetry/batch"), user).content(batch))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(2));
        mockMvc.perform(auth(post("/v1/telemetry/batch"), user).content(batch))   // resend: ingestion is idempotent
                .andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject("select count(*) from journey_events where id = ?", Integer.class, journeyEvent))
                .isEqualTo(1);
        String props = jdbc.queryForObject("select props from journey_events where id = ?", String.class, journeyEvent);
        assertThat(props).contains("[redacted]").doesNotContain("491701234567");
        assertThat(jdbc.queryForObject(
                "select coalesce(sum(calls),0) from telemetry_rollups where endpoint = '/v1/plans' and network_type = 'wifi'",
                Long.class)).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void telemetry_rejectsMalformedBatches() throws Exception {
        UUID user = UUID.randomUUID();
        mockMvc.perform(auth(post("/v1/telemetry/batch"), user)
                        .content("{\"sessionId\":\"x\",\"appVersion\":\"1.0\",\"events\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
    }

    @Test
    void deviceTokensCanBeRegisteredAndRemoved() throws Exception {
        UUID user = UUID.randomUUID();
        String token = "{\"token\":\"fcm_device_token_abcdefghijklmnop\"}";

        mockMvc.perform(auth(put("/v1/devices"), user).content(token)).andExpect(status().isNoContent());
        mockMvc.perform(auth(put("/v1/devices"), user).content(token)).andExpect(status().isNoContent());   // idempotent
        assertThat(jdbc.queryForObject("select count(*) from device_tokens where user_id = ? and active", Integer.class, user))
                .isEqualTo(1);

        mockMvc.perform(auth(delete("/v1/devices"), user).content(token)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from device_tokens where user_id = ? and active", Integer.class, user))
                .isZero();
        mockMvc.perform(auth(put("/v1/devices"), user).content("{\"token\":\"short\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void twoPayRequestsAtTheSameTimeProduceOneCharge() throws Exception {
        UUID user = UUID.randomUUID();
        UUID number = linkNumber(user, "+491701234599");
        UUID orderId = newOrder(user, number, planId("S-28"));

        var first = java.util.concurrent.CompletableFuture.runAsync(() -> payQuietly(user, orderId));
        var second = java.util.concurrent.CompletableFuture.runAsync(() -> payQuietly(user, orderId));
        java.util.concurrent.CompletableFuture.allOf(first, second).get(20, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        awaitStatus(orderId, "COMPLETED");
    }

    private void payQuietly(UUID user, UUID orderId) {
        try {
            pay(user, orderId, "tok_success").andExpect(status().isOk());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
