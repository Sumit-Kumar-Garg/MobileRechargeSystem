package com.app.recharge.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.recharge.support.AbstractIntegrationTest;
import com.app.recharge.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The advisor agent end to end with the scripted stand-in model: HTTP -> rules now -> agent in the background
 * (tools read real tables) -> stored advice -> served on the next request.
 */
@TestPropertySource(properties = "app.advisor.llm=scripted")
class AdvisorIT extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, UUID user) {
        return b.header("Authorization", TestJwt.bearer(user)).contentType(MediaType.APPLICATION_JSON);
    }

    private UUID numberWithUsage(UUID user, String msisdn, int dataMb) throws Exception {
        String json = mockMvc.perform(auth(post("/v1/numbers"), user).content("{\"msisdn\":\"" + msisdn + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID number = UUID.fromString(JsonPath.read(json, "$.id"));
        mockMvc.perform(auth(post("/v1/demo/usage"), user)
                .content("{\"numberId\":\"" + number + "\",\"dataMbUsed\":" + dataMb + "}")).andExpect(status().isNoContent());
        return number;
    }

    @Test
    void theFirstRequestIsAnsweredByTheRulesAndTheNextOneByTheAgent() throws Exception {
        UUID user = UUID.randomUUID();
        UUID number = numberWithUsage(user, "+491701239001", 9500);
        mockMvc.perform(auth(put("/v1/consents/personalization"), user).content("{\"granted\":true}"))
                .andExpect(status().isOk());

        // no advice yet: the rules answer at once, the agent starts in the background
        mockMvc.perform(auth(get("/v1/recommendations").param("numberId", number.toString()), user)
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("USAGE_FIT"))
                .andExpect(jsonPath("$.explanation").doesNotExist());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from recommendation_advice where number_id = ?", Integer.class, number)).isEqualTo(1));

        mockMvc.perform(auth(get("/v1/recommendations").param("numberId", number.toString()), user)
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("AI_ADVISOR"))
                .andExpect(jsonPath("$.planCode").value("L-28"))
                .andExpect(jsonPath("$.personalized").value(true))
                .andExpect(jsonPath("$.modelVersion").value("agent:scripted-demo"))
                .andExpect(jsonPath("$.explanation").value(org.hamcrest.Matchers.containsString("9.5 GB")));
    }

    @Test
    void withoutConsentTheAgentNeverRunsAndWithdrawingConsentRemovesItsAdvice() throws Exception {
        UUID user = UUID.randomUUID();
        UUID number = numberWithUsage(user, "+491701239002", 9500);

        mockMvc.perform(auth(get("/v1/recommendations").param("numberId", number.toString()), user))
                .andExpect(jsonPath("$.reason").value("POPULAR_DEFAULT"));
        assertThat(jdbc.queryForObject("select count(*) from recommendation_advice where number_id = ?",
                Integer.class, number)).isZero();

        mockMvc.perform(auth(put("/v1/consents/personalization"), user).content("{\"granted\":true}"));
        mockMvc.perform(auth(get("/v1/recommendations").param("numberId", number.toString()), user)
                .header("Accept-Language", "en"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from recommendation_advice where number_id = ?", Integer.class, number)).isEqualTo(1));

        mockMvc.perform(auth(put("/v1/consents/personalization"), user).content("{\"granted\":false}"));
        mockMvc.perform(auth(get("/v1/recommendations").param("numberId", number.toString()), user))
                .andExpect(jsonPath("$.reason").value("POPULAR_DEFAULT"))
                .andExpect(jsonPath("$.explanation").doesNotExist());
        assertThat(jdbc.queryForObject("select count(*) from recommendation_advice where number_id = ?",
                Integer.class, number)).isZero();
    }
}
