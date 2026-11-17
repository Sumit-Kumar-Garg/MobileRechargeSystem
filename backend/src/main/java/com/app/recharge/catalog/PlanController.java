package com.app.recharge.catalog;

import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    /**
     * GET /v1/plans?market=XX: lists the active plans of a market, cheapest first. Public endpoint; the response may be
     * cached by browsers and CDNs for 60 seconds.
     */
    @GetMapping
    public ResponseEntity<PlansResponse> list(
            @RequestParam
            @Pattern(regexp = "^[A-Z]{2}$", message = "market must be a 2-letter uppercase country code")
            String market) {
        PlansResponse body = new PlansResponse(market, planService.getActivePlans(market));
        // The catalog is identical for every caller, so it is safe for shared caches (CDN) for a short time.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(body);
    }
}
