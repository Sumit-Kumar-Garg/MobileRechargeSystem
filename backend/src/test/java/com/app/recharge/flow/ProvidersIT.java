package com.app.recharge.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.recharge.catalog.CatalogSyncService;
import com.app.recharge.catalog.spi.PlanProvider;
import com.app.recharge.catalog.spi.ProviderPlan;
import com.app.recharge.support.AbstractIntegrationTest;
import com.app.recharge.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Several payment providers and several plan providers working side by side.
 * Markets NL and AT are used so the plans created here do not disturb the other integration tests.
 */
class ProvidersIT extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogSyncService catalogSync;

    private record Feed(String id, List<ProviderPlan> plans, boolean broken) implements PlanProvider {
        @Override public List<ProviderPlan> fetchPlans() {
            if (broken) {
                throw new IllegalStateException("partner API is down");
            }
            return plans;
        }
    }

    private static ProviderPlan plan(String code, String market, int price) {
        return new ProviderPlan(code, market, "Partner " + code, price, "EUR", 7, 2000, null, null, List.of("Partner benefit"));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, UUID user) {
        return b.header("Authorization", TestJwt.bearer(user)).contentType(MediaType.APPLICATION_JSON);
    }

    private UUID linkNumber(UUID user, String msisdn) throws Exception {
        String json = mockMvc.perform(auth(post("/v1/numbers"), user).content("{\"msisdn\":\"" + msisdn + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private UUID planId(String market, String code) throws Exception {
        String json = mockMvc.perform(get("/v1/plans").param("market", market))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(json, "$.plans[?(@.code=='" + code + "')].id");
        return UUID.fromString(ids.get(0));
    }

    private UUID newOrder(UUID user, UUID numberId, UUID planId) throws Exception {
        String json = mockMvc.perform(auth(post("/v1/orders"), user)
                        .header("Idempotency-Key", UUID.randomUUID().toString().replace("-", ""))
                        .content("{\"numberId\":\"" + numberId + "\",\"planId\":\"" + planId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private void awaitStatus(UUID orderId, String expected) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, orderId))
                        .isEqualTo(expected));
    }

    // ---------------- payment providers ----------------

    @Test
    void paymentMethods_areListedWithoutExposingProviders() throws Exception {
        mockMvc.perform(auth(get("/v1/payment-methods").param("currency", "EUR"), UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.methods", hasItems("card", "wallet")))
                .andExpect(jsonPath("$.providers").doesNotExist());
        mockMvc.perform(auth(get("/v1/payment-methods").param("currency", "eur"), UUID.randomUUID()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cardGoesToTheFirstProviderInThePriorityList_walletToTheOnlyProviderThatOffersIt() throws Exception {
        UUID user = UUID.randomUUID();
        UUID number = linkNumber(user, "+491701237001");
        UUID plan = planId("DE", "S-28");

        UUID cardOrder = newOrder(user, number, plan);
        mockMvc.perform(auth(post("/v1/orders/" + cardOrder + "/pay"), user)
                .content("{\"paymentToken\":\"tok_success\"}")).andExpect(status().isOk());
        awaitStatus(cardOrder, "COMPLETED");

        UUID walletOrder = newOrder(user, number, plan);
        mockMvc.perform(auth(post("/v1/orders/" + walletOrder + "/pay"), user)
                .content("{\"paymentToken\":\"tok_success\",\"method\":\"wallet\"}")).andExpect(status().isOk());
        awaitStatus(walletOrder, "COMPLETED");

        assertThat(jdbc.queryForObject("select provider from payments where order_id = ?", String.class, cardOrder))
                .isEqualTo("mockpay");
        assertThat(jdbc.queryForObject("select provider || ':' || method from payments where order_id = ?", String.class, walletOrder))
                .isEqualTo("altpay:wallet");
    }

    @Test
    void anUnsupportedMethodIsRejectedAndNothingIsLeftPending() throws Exception {
        UUID user = UUID.randomUUID();
        UUID order = newOrder(user, linkNumber(user, "+491701237002"), planId("DE", "S-28"));

        mockMvc.perform(auth(post("/v1/orders/" + order + "/pay"), user)
                        .content("{\"paymentToken\":\"tok_success\",\"method\":\"crypto\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_METHOD_UNAVAILABLE"));

        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, order)).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, order)).isZero();
    }

    @Test
    void anUnknownOutcomeStaysWithTheSameProvider() throws Exception {
        UUID user = UUID.randomUUID();
        UUID order = newOrder(user, linkNumber(user, "+491701237003"), planId("DE", "S-28"));

        mockMvc.perform(auth(post("/v1/orders/" + order + "/pay"), user)
                        .content("{\"paymentToken\":\"tok_down\",\"method\":\"wallet\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));
        // a second tap must not start a payment with another provider
        mockMvc.perform(auth(post("/v1/orders/" + order + "/pay"), user)
                        .content("{\"paymentToken\":\"tok_success\"}"))
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));

        assertThat(jdbc.queryForList("select provider from payments where order_id = ?", String.class, order))
                .containsExactly("altpay");
    }

    // ---------------- plan providers ----------------

    @Test
    void aPartnersPlansAreSyncedSoldAndFulfilledByThatPartner() throws Exception {
        // "travelsim" has a fulfilment system in this deployment (demo profile), so its plans can be sold
        var result = catalogSync.sync(new Feed("travelsim", List.of(plan("NL-TRAVEL-7", "NL", 900)), false));
        assertThat(result.failed()).isFalse();

        mockMvc.perform(get("/v1/plans").param("market", "NL"))
                .andExpect(jsonPath("$.plans[?(@.code=='NL-TRAVEL-7')].provider").value("travelsim"))
                .andExpect(jsonPath("$.plans[?(@.code=='NL-TRAVEL-7')].price.amountMinor").value(900));

        UUID user = UUID.randomUUID();
        UUID order = newOrder(user, linkNumber(user, "+31612345678"), planId("NL", "NL-TRAVEL-7"));
        mockMvc.perform(auth(post("/v1/orders/" + order + "/pay"), user)
                .content("{\"paymentToken\":\"tok_success\"}")).andExpect(status().isOk());
        awaitStatus(order, "COMPLETED");

        assertThat(jdbc.queryForObject("select plan_provider from orders where id = ?", String.class, order))
                .isEqualTo("travelsim");
    }

    @Test
    void aPlanFromAProviderWithoutFulfilmentCannotBeOrdered() throws Exception {
        catalogSync.sync(new Feed("no-fulfilment", List.of(plan("AT-ORPHAN", "AT", 500)), false));
        UUID user = UUID.randomUUID();
        UUID number = linkNumber(user, "+43664123456");

        mockMvc.perform(auth(post("/v1/orders"), user)
                        .header("Idempotency-Key", UUID.randomUUID().toString().replace("-", ""))
                        .content("{\"numberId\":\"" + number + "\",\"planId\":\"" + planId("AT", "AT-ORPHAN") + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLAN_UNAVAILABLE"));
    }

    @Test
    void syncIsSafe_updatesInPlace_deactivatesMissing_skipsInvalid_andSurvivesABrokenFeed() throws Exception {
        String provider = "sync-" + UUID.randomUUID().toString().substring(0, 8);
        catalogSync.sync(new Feed(provider, List.of(plan("A", "AT", 500), plan("B", "AT", 700)), false));
        UUID idOfA = jdbc.queryForObject("select id from plans where provider_id = ? and code = 'A'", UUID.class, provider);

        // price change + B removed + one invalid plan in the feed
        var second = catalogSync.sync(new Feed(provider, List.of(plan("A", "AT", 550), plan("FREE", "AT", 0)), false));
        assertThat(second.upserted()).isEqualTo(1);
        assertThat(second.deactivated()).isEqualTo(1);
        assertThat(second.rejected()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select price_minor from plans where id = ?", Integer.class, idOfA)).isEqualTo(550);
        assertThat(jdbc.queryForObject("select active from plans where provider_id = ? and code = 'B'", Boolean.class, provider)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from plans where provider_id = ? and code = 'FREE'", Integer.class, provider)).isZero();

        // a broken or empty feed changes nothing
        assertThat(catalogSync.sync(new Feed(provider, List.of(), true)).failed()).isTrue();
        assertThat(catalogSync.sync(new Feed(provider, List.of(), false)).failed()).isTrue();
        assertThat(jdbc.queryForObject("select active from plans where id = ?", Boolean.class, idOfA)).isTrue();

        // another provider may use the same plan code without clashing
        String other = "sync-" + UUID.randomUUID().toString().substring(0, 8);
        catalogSync.sync(new Feed(other, List.of(plan("A", "AT", 999)), false));
        assertThat(jdbc.queryForObject("select count(*) from plans where code = 'A' and market = 'AT' and active", Integer.class))
                .isGreaterThanOrEqualTo(2);
    }
}
