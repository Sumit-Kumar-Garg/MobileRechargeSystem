package com.app.recharge.number;

import com.app.recharge.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/numbers")
public class NumberController {

    private final NumberService service;

    public NumberController(NumberService service) {
        this.service = service;
    }

    /** GET /v1/numbers: the caller's linked numbers, masked. */
    @GetMapping
    public List<NumberView> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(CurrentUser.id(jwt));
    }

    /** Idempotent: linking the same number twice returns the same record. */
    @PostMapping
    public NumberView link(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LinkNumberRequest request) {
        return service.link(CurrentUser.id(jwt), request.msisdn());
    }

    public record LinkNumberRequest(@NotBlank @Size(max = 24) String msisdn) { }
}
