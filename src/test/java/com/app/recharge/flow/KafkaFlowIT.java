package com.app.recharge.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.recharge.common.Topics;
import com.app.recharge.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The asynchronous half of the system against a real broker: outbox row -> Kafka -> consumer -> database.
 * Uses its own Postgres so its outbox publisher never picks up rows written by the other integration tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "kafka-it"})
@Tag("integration")
class KafkaFlowIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired MeterRegistry meters;

    @Test
    void aCompletedOrderReachesTheCustomerAndTheJourneyStoreThroughKafka() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = completedOrder(user);

        // outbox -> recharge.order.events.v1 -> notification consumer
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                assertThat(count("select count(*) from notifications where order_id = ? and event_type = 'OrderCompleted' "
                        + "and status = 'SENT'", orderId)).isGreaterThanOrEqualTo(1));

        // outbox -> recharge.client.telemetry.v1 -> telemetry consumer
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                assertThat(count("select count(*) from journey_events where order_id = ? and name = 'recharge_completed' "
                        + "and source = 'server'", orderId)).isEqualTo(1));

        // every event of this order left the outbox
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("select count(*) from outbox_events where event_key = ? and published_at is null",
                        orderId.toString())).isZero());
    }

    @Test
    void deliveringTheSameEventTwiceSendsOneNotification() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = completedOrder(user);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(count("select count(*) from notifications where order_id = ? and status = 'SENT'", orderId))
                        .isGreaterThanOrEqualTo(1));
        int before = count("select count(*) from notifications where order_id = ?", orderId);

        // at-least-once delivery: replay the published event exactly as the outbox sent it
        String payload = jdbc.queryForObject(
                "select payload from outbox_events where event_key = ? and type = 'OrderCompleted'", String.class,
                orderId.toString());
        kafka.send(Topics.ORDER_EVENTS, orderId.toString(), payload).get();
        kafka.send(Topics.ORDER_EVENTS, orderId.toString(), payload).get();

        // give the consumer time to process both copies, then nothing may have changed
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(count("select count(*) from notifications where order_id = ?", orderId)).isEqualTo(before));
    }

    @Test
    void anUnreadableMessageGoesToTheDeadLetterTopicWithoutBlockingTheOnesBehindIt() throws Exception {
        double before = dltCount();

        kafka.send(Topics.ORDER_EVENTS, "poison", "{ this is not an order event").get();
        UUID orderId = completedOrder(UUID.randomUUID());          // published after the poison message

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(dltCount()).isGreaterThanOrEqualTo(before + 1));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(count("select count(*) from notifications where order_id = ? and status = 'SENT'", orderId))
                        .isGreaterThanOrEqualTo(1));
    }

    // ---------------- helpers ----------------

    private double dltCount() {
        Counter counter = meters.find("recharge.kafka.dlt").tag("topic", Topics.ORDER_EVENTS).counter();
        return counter == null ? 0 : counter.count();
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, UUID user) {
        return b.header("Authorization", TestJwt.bearer(user)).contentType(MediaType.APPLICATION_JSON);
    }

    /** Links a number, orders the S-28 plan, pays with a token the mock approves, waits for COMPLETED. */
    private UUID completedOrder(UUID user) throws Exception {
        String msisdn = "+4917" + String.format("%08d", (int) (Math.random() * 100_000_000));
        String numberJson = mockMvc.perform(auth(post("/v1/numbers"), user).content("{\"msisdn\":\"" + msisdn + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String numberId = JsonPath.read(numberJson, "$.id");

        String plansJson = mockMvc.perform(get("/v1/plans").param("market", "DE"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> planIds = JsonPath.read(plansJson, "$.plans[?(@.code=='S-28')].id");

        String orderJson = mockMvc.perform(auth(post("/v1/orders"), user)
                        .header("Idempotency-Key", UUID.randomUUID().toString().replace("-", ""))
                        .content("{\"numberId\":\"" + numberId + "\",\"planId\":\"" + planIds.get(0) + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(JsonPath.read(orderJson, "$.id"));

        mockMvc.perform(auth(post("/v1/orders/" + orderId + "/pay"), user).content("{\"paymentToken\":\"tok_success\"}"))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, orderId))
                        .isEqualTo("COMPLETED"));
        return orderId;
    }
}
