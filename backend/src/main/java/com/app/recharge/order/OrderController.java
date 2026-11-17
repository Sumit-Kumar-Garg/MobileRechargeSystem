package com.app.recharge.order;

import com.app.recharge.payment.PaymentService;
import com.app.recharge.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/orders")
public class OrderController {

    private final OrderService orders;
    private final PaymentService payments;

    public OrderController(OrderService orders, PaymentService payments) {
        this.orders = orders;
        this.payments = payments;
    }

    /**
     * POST /v1/orders: creates an order for one of the caller's numbers and a plan. Idempotent per Idempotency-Key: a
     * retry returns the same order. Returns 201 with the order in CREATED state.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9_-]{16,64}$", message = "Idempotency-Key must be 16-64 chars [A-Za-z0-9_-]")
            String idempotencyKey,
            @RequestHeader(value = "X-Journey-Id", required = false) UUID journeyId,
            @Valid @RequestBody CreateOrderRequest request) {
        return orders.create(CurrentUser.id(jwt),
                new CreateOrderCommand(request.numberId(), request.planId(), idempotencyKey, journeyId));
    }

    /** GET /v1/orders/{id}: one of the caller's orders; other users' orders return 404. */
    @GetMapping("/{id}")
    public OrderView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return orders.get(CurrentUser.id(jwt), id);
    }

    /** GET /v1/orders: the caller's 20 most recent orders, newest first. */
    @GetMapping
    public List<OrderView> recent(@AuthenticationPrincipal Jwt jwt) {
        return orders.recent(CurrentUser.id(jwt));
    }

    /** Returns the order as it stands after the attempt (PAID/COMPLETED, PAYMENT_FAILED, or PAYMENT_PENDING). */
    @PostMapping("/{id}/pay")
    public OrderView pay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                         @Valid @RequestBody PayRequest request) {
        return payments.pay(CurrentUser.id(jwt), id, request.paymentToken(), request.method());
    }

    public record CreateOrderRequest(@NotNull UUID numberId, @NotNull UUID planId) { }

    /** {@code method} is optional and defaults to "card". Clients never name a provider; the server routes. */
    public record PayRequest(
            @NotBlank @Size(max = 64) String paymentToken,
            @Pattern(regexp = "^[a-z_]{2,30}$", message = "method must be lower case letters") String method) { }
}
