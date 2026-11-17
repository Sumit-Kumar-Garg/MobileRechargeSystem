package com.app.recharge.consent;

import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import com.app.recharge.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/consents")
public class ConsentController {

    private final ConsentService service;

    public ConsentController(ConsentService service) {
        this.service = service;
    }

    /** GET /v1/consents: the caller's consent for every purpose, e.g. {"personalization": true, "analytics": false}. */
    @GetMapping
    public Map<String, Boolean> get(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        service.all(CurrentUser.id(jwt)).forEach((k, v) -> out.put(k.name().toLowerCase(Locale.ROOT), v));
        return out;
    }

    /**
     * PUT /v1/consents/{purpose}: grants or withdraws one consent (purpose is case-insensitive) and returns all of the
     * caller's consents. Unknown purposes are a 400.
     */
    @PutMapping("/{purpose}")
    public Map<String, Boolean> set(@AuthenticationPrincipal Jwt jwt, @PathVariable String purpose,
                                    @Valid @RequestBody ConsentRequest request) {
        ConsentPurpose p;
        try {
            p = ConsentPurpose.valueOf(purpose.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "Unknown consent purpose");
        }
        service.set(CurrentUser.id(jwt), p, request.granted());
        return get(jwt);
    }

    public record ConsentRequest(@NotNull Boolean granted) { }
}
