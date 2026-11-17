package com.app.recharge.telemetry;

import com.app.recharge.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/telemetry")
public class TelemetryController {

    private final TelemetryService service;

    public TelemetryController(TelemetryService service) {
        this.service = service;
    }

    /**
     * POST /v1/telemetry/batch: accepts up to 100 client events and returns 202 with how many were accepted and
     * dropped.
     */
    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TelemetryService.IngestResult batch(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody TelemetryBatchRequest request) {
        return service.ingest(CurrentUser.id(jwt), request);
    }
}
