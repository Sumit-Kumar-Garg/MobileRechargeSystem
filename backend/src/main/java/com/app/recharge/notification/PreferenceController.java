package com.app.recharge.notification;

import com.app.recharge.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/notification-preferences")
public class PreferenceController {

    private final PreferenceService service;

    public PreferenceController(PreferenceService service) {
        this.service = service;
    }

    /** GET /v1/notification-preferences: the caller's channel preferences, locale and email (defaults if never set). */
    @GetMapping
    public PreferenceService.Preferences get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(CurrentUser.id(jwt));
    }

    /** PUT /v1/notification-preferences: replaces the caller's preferences and returns them. */
    @PutMapping
    public PreferenceService.Preferences put(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody PreferenceRequest request) {
        return service.save(CurrentUser.id(jwt), new PreferenceService.Preferences(
                request.pushEnabled(), request.smsEnabled(), request.emailEnabled(), request.locale(), request.email()));
    }

    public record PreferenceRequest(
            @NotNull Boolean pushEnabled,
            @NotNull Boolean smsEnabled,
            @NotNull Boolean emailEnabled,
            @NotNull @Pattern(regexp = "^(de|en)(-[A-Z]{2})?$") String locale,
            @Email @Size(max = 254) String email) { }
}
