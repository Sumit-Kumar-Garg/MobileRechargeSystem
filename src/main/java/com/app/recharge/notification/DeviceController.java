package com.app.recharge.notification;

import com.app.recharge.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/devices")
public class DeviceController {

    private static final String TOKEN_PATTERN = "^[A-Za-z0-9:_\\-.]{20,512}$";

    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    /** Called on first launch and whenever FCM rotates the token. Idempotent. */
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeviceRequest request) {
        service.register(CurrentUser.id(jwt), "ANDROID", request.token());
    }

    /** Called on logout so a shared phone stops receiving this user's receipts. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeviceRequest request) {
        service.unregister(CurrentUser.id(jwt), request.token());
    }

    public record DeviceRequest(@NotBlank @Pattern(regexp = TOKEN_PATTERN) String token) { }
}
