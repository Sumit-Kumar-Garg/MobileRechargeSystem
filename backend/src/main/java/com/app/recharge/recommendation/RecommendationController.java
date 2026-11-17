package com.app.recharge.recommendation;

import com.app.recharge.security.CurrentUser;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/recommendations")
public class RecommendationController {

    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    /** 204 when there is nothing to recommend (empty catalog). Clients treat any failure as "no badge". */
    @GetMapping
    public ResponseEntity<RecommendationView> get(@AuthenticationPrincipal Jwt jwt, @RequestParam UUID numberId,
                                                  Locale locale) {
        // locale comes from Accept-Language; it only selects the language of the advisor's sentence
        return service.recommend(CurrentUser.id(jwt), numberId, locale == null ? "en" : locale.getLanguage())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
