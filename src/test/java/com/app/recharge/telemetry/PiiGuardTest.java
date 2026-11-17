package com.app.recharge.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PiiGuardTest {

    private final PiiGuard guard = new PiiGuard();

    @Test
    void keepsShortPrimitivesAndBenignStrings() {
        PiiGuard.Result r = guard.sanitize(Map.of(
                "durationMs", 123, "recommended", true, "plan", "M-28", "networkType", "wifi",
                "version", "1.2.3", "day", "2026-11-07"));

        assertThat(r.redactions()).isZero();
        assertThat(r.props()).containsEntry("durationMs", 123).containsEntry("plan", "M-28")
                .containsEntry("day", "2026-11-07").containsEntry("version", "1.2.3");
    }

    @Test
    void redactsEmailsAndPhoneNumbers() {
        PiiGuard.Result r = guard.sanitize(Map.of(
                "note", "write to jane.doe@example.com",
                "number", "+49 170 1234567",
                "digits", "4915112345678"));

        assertThat(r.redactions()).isEqualTo(3);
        assertThat(r.props().values()).containsOnly("[redacted]");
    }

    @Test
    void dropsInvalidKeysNestedObjectsAndNulls() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("bad key!", "x");
        input.put("nested", Map.of("a", 1));
        input.put("list", List.of(1, 2));
        input.put("nothing", null);
        input.put("ok", 1);

        assertThat(guard.sanitize(input).props()).containsOnlyKeys("ok");
    }

    @Test
    void truncatesLongValues() {
        String longValue = "x".repeat(500);
        String out = (String) guard.sanitize(Map.of("v", longValue)).props().get("v");
        assertThat(out).hasSize(PiiGuard.MAX_VALUE_LENGTH);
    }

    @Test
    void capsTheNumberOfProperties() {
        Map<String, Object> input = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) {
            input.put("k" + i, i);
        }
        assertThat(guard.sanitize(input).props()).hasSize(PiiGuard.MAX_PROPS);
    }

    @Test
    void nullInputIsEmpty() {
        assertThat(guard.sanitize(null).props()).isEmpty();
    }
}
