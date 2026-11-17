package com.app.recharge.payment;

import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Lets clients show the available payment methods without knowing anything about providers. */
@RestController
@RequestMapping("/v1/payment-methods")
public class PaymentMethodController {

    private final PaymentRouter router;

    public PaymentMethodController(PaymentRouter router) {
        this.router = router;
    }

    /** GET /v1/payment-methods?currency=EUR: the payment methods some provider accepts for this currency, sorted. */
    @GetMapping
    public MethodsResponse list(
            @RequestParam @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an ISO 4217 code") String currency) {
        return new MethodsResponse(currency, router.methods(currency));
    }

    public record MethodsResponse(String currency, List<String> methods) { }
}
