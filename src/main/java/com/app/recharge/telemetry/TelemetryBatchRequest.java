package com.app.recharge.telemetry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TelemetryBatchRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{8,64}$") String sessionId,
        @NotBlank @Pattern(regexp = "^[0-9A-Za-z.\\-]{1,20}$") String appVersion,
        @NotEmpty @Size(max = 100) List<@Valid EventDto> events) {

    public record EventDto(
            @NotBlank String id,
            @NotBlank @Pattern(regexp = "^(journey|network_call|lifecycle|config_change)$") String type,
            @NotBlank @Pattern(regexp = "^[a-z0-9_]{1,40}$") String name,
            Instant occurredAt,
            UUID journeyId,
            UUID orderId,
            Map<String, Object> props) { }
}
