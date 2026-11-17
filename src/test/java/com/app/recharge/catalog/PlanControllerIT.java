package com.app.recharge.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.recharge.support.AbstractIntegrationTest;
import com.app.recharge.support.TestJwt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class PlanControllerIT extends AbstractIntegrationTest {

    @Test
    void listsActivePlansForMarketSortedByPriceWithBenefits() throws Exception {
        mockMvc.perform(get("/v1/plans").param("market", "DE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market").value("DE"))
                .andExpect(jsonPath("$.plans", hasSize(3)))
                .andExpect(jsonPath("$.plans[0].code").value("S-28"))
                .andExpect(jsonPath("$.plans[0].price.amountMinor").value(1000))
                .andExpect(jsonPath("$.plans[0].price.currency").value("EUR"))
                .andExpect(jsonPath("$.plans[0].validityDays").value(28))
                .andExpect(jsonPath("$.plans[0].benefits", hasSize(2)))
                .andExpect(jsonPath("$.plans[0].benefits[0].type").value("NETWORK"))
                .andExpect(jsonPath("$.plans[2].code").value("L-28"))
                .andExpect(jsonPath("$.plans[2].benefits", hasSize(3)))
                .andExpect(header().string("Cache-Control", containsString("max-age=60")));
    }

    @Test
    void doesNotLeakPlansOfOtherMarkets() throws Exception {
        mockMvc.perform(get("/v1/plans").param("market", "PL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans", hasSize(1)))
                .andExpect(jsonPath("$.plans[0].price.currency").value("PLN"));
    }

    @Test
    void unknownMarketReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/v1/plans").param("market", "ZZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans", hasSize(0)));
    }

    @Test
    void rejectsMalformedMarketWithProblemJson() throws Exception {
        mockMvc.perform(get("/v1/plans").param("market", "de"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
                .andExpect(jsonPath("$.correlationId", notNullValue()));
    }

    @Test
    void rejectsMissingMarket() throws Exception {
        mockMvc.perform(get("/v1/plans"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
    }

    @Test
    void unknownRouteReturnsNotFoundProblem() throws Exception {
        mockMvc.perform(get("/v1/does-not-exist")
                        .header("Authorization", TestJwt.bearer(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void unauthenticatedRouteIsRejectedWith401Problem() throws Exception {
        mockMvc.perform(get("/v1/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void echoesSafeCorrelationId() throws Exception {
        mockMvc.perform(get("/v1/plans").param("market", "DE").header("X-Correlation-Id", "test-corr-1234"))
                .andExpect(header().string("X-Correlation-Id", "test-corr-1234"));
    }

    @Test
    void replacesUnsafeCorrelationId() throws Exception {
        String unsafe = "bad id with spaces & symbols";
        MvcResult result = mockMvc.perform(get("/v1/plans").param("market", "DE").header("X-Correlation-Id", unsafe))
                .andExpect(header().string("X-Correlation-Id", not(unsafe)))
                .andReturn();
        assertThat(result.getResponse().getHeader("X-Correlation-Id")).matches("^[A-Za-z0-9-]{8,64}$");
    }
}
