package com.app.recharge.order;

import com.app.recharge.catalog.PlanService;
import com.app.recharge.catalog.PlanView;
import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import com.app.recharge.number.NumberService;
import com.app.recharge.number.SubscriberNumber;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final NumberService numbers;
    private final PlanService plans;
    private final OrderCreator creator;
    private final FulfilmentDirectory fulfilment;

    public OrderService(OrderRepository orders, NumberService numbers, PlanService plans, OrderCreator creator,
                        FulfilmentDirectory fulfilment) {
        this.orders = orders;
        this.numbers = numbers;
        this.plans = plans;
        this.creator = creator;
        this.fulfilment = fulfilment;
    }

    /**
     * Idempotent per (user, Idempotency-Key). Replays return the original order; reusing a key with a
     * different request is rejected. Price comes from the server-side plan, never from the client.
     */
    public OrderView create(UUID userId, CreateOrderCommand cmd) {
        SubscriberNumber number = numbers.requireOwned(userId, cmd.numberId());
        String fingerprint = cmd.numberId() + ":" + cmd.planId();

        Optional<RechargeOrder> existing = orders.findByUserIdAndIdempotencyKey(userId, cmd.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        PlanView plan = plans.getActivePlan(cmd.planId());
        if (!plan.market().equals(number.getMarket())) {
            throw new BusinessException(ErrorCode.PLAN_UNAVAILABLE, "Plan is not available for this number");
        }
        if (!fulfilment.canFulfil(plan.provider())) {
            // Never take money for a plan whose provider has no fulfilment system configured here.
            throw new BusinessException(ErrorCode.PLAN_UNAVAILABLE, "Plan is not available right now");
        }
        try {
            return OrderView.from(creator.create(userId, number, plan, cmd.idempotencyKey(), fingerprint, cmd.journeyId()));
        } catch (DataIntegrityViolationException race) {
            RechargeOrder winner = orders.findByUserIdAndIdempotencyKey(userId, cmd.idempotencyKey())
                    .orElseThrow(() -> race);
            return replay(winner, fingerprint);
        }
    }

    /** One of the user's orders, or RESOURCE_NOT_FOUND if it does not exist or is someone else's. */
    public OrderView get(UUID userId, UUID orderId) {
        return orders.findByIdAndUserId(orderId, userId)
                .map(OrderView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found"));
    }

    /** The user's 20 most recent orders, newest first. */
    public List<OrderView> recent(UUID userId) {
        return orders.findTop20ByUserIdOrderByCreatedAtDesc(userId).stream().map(OrderView::from).toList();
    }

    /**
     * Returns the existing order for a repeated Idempotency-Key, or IDEMPOTENCY_KEY_REUSED if the key was used for a
     * different number or plan.
     */
    private OrderView replay(RechargeOrder order, String fingerprint) {
        if (!order.getRequestFingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used for a different request");
        }
        return OrderView.from(order);
    }
}
