package com.app.recharge.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.recharge.catalog.PlanService;
import com.app.recharge.catalog.PlanView;
import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import com.app.recharge.number.NumberService;
import com.app.recharge.number.SubscriberNumber;
import com.app.recharge.support.TestData;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock OrderRepository orders;
    @Mock NumberService numbers;
    @Mock PlanService plans;
    @Mock OrderCreator creator;

    OrderService service;
    final UUID user = UUID.randomUUID();
    final String key = "idem-key-idem-key-1";
    SubscriberNumber number;
    PlanView plan;

    @BeforeEach
    void setUp() {
        service = new OrderService(orders, numbers, plans, creator, provider -> !"nobody".equals(provider));
        number = TestData.number(user);
        plan = TestData.plan("M-28", 1500, 10000);
        when(numbers.requireOwned(user, number.getId())).thenReturn(number);
        when(plans.getActivePlan(plan.id())).thenReturn(plan);
        when(orders.findByUserIdAndIdempotencyKey(user, key)).thenReturn(Optional.empty());
    }

    private CreateOrderCommand cmd() {
        return new CreateOrderCommand(number.getId(), plan.id(), key, null);
    }

    private RechargeOrder newOrder() {
        return RechargeOrder.create(user, number.getId(), number.getMsisdnMasked(), plan, key,
                number.getId() + ":" + plan.id(), null, Instant.now(), java.time.Duration.ofMinutes(15));
    }

    @Test
    void createsOrderWithServerSidePrice() {
        RechargeOrder created = newOrder();
        when(creator.create(eq(user), eq(number), eq(plan), eq(key), any(), any())).thenReturn(created);

        OrderView view = service.create(user, cmd());

        assertThat(view.amountMinor()).isEqualTo(1500);
        assertThat(view.status()).isEqualTo("CREATED");
    }

    @Test
    void replayWithSameKeyAndSameRequestReturnsOriginal() {
        RechargeOrder existing = newOrder();
        when(orders.findByUserIdAndIdempotencyKey(user, key)).thenReturn(Optional.of(existing));

        OrderView view = service.create(user, cmd());

        assertThat(view.id()).isEqualTo(existing.getId());
        verify(creator, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() {
        RechargeOrder existing = RechargeOrder.create(user, number.getId(), "m", plan, key,
                number.getId() + ":" + UUID.randomUUID(), null, Instant.now(), java.time.Duration.ofMinutes(15));
        when(orders.findByUserIdAndIdempotencyKey(user, key)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(user, cmd()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void concurrentDuplicateReturnsTheWinner() {
        RechargeOrder winner = newOrder();
        when(creator.create(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(orders.findByUserIdAndIdempotencyKey(user, key))
                .thenReturn(Optional.empty())          // first look: nothing yet
                .thenReturn(Optional.of(winner));      // after the race: the winner exists

        OrderView view = service.create(user, cmd());

        assertThat(view.id()).isEqualTo(winner.getId());
    }

    @Test
    void planFromAnotherMarketIsRejected() {
        PlanView polish = new PlanView(plan.id(), "S-28", "PL", "Prepaid S", plan.price(), 28, plan.allowances(), plan.benefits());
        when(plans.getActivePlan(plan.id())).thenReturn(polish);

        assertThatThrownBy(() -> service.create(user, cmd()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PLAN_UNAVAILABLE));
        verify(creator, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aPlanWhoseProviderHasNoFulfilmentSystemIsNotSold() {
        PlanView orphan = new PlanView(plan.id(), "nobody", "X-1", "DE", "Orphan", plan.price(), 28, plan.allowances(), plan.benefits());
        when(plans.getActivePlan(plan.id())).thenReturn(orphan);

        assertThatThrownBy(() -> service.create(user, cmd()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PLAN_UNAVAILABLE));
        verify(creator, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void theOrderRemembersWhoSuppliesThePlan() {
        PlanView partner = new PlanView(plan.id(), "travelsim", "TRAVEL-7", "DE", "Travel", plan.price(), 7, plan.allowances(), plan.benefits());
        RechargeOrder order = RechargeOrder.create(user, number.getId(), "m", partner, key, "f", null, Instant.now(),
                java.time.Duration.ofMinutes(15));
        assertThat(order.getPlanProvider()).isEqualTo("travelsim");
        assertThat(newOrder().getPlanProvider()).isEqualTo("operator");
    }

    @Test
    void numberOfAnotherUserIsNotFound() {
        UUID other = UUID.randomUUID();
        when(numbers.requireOwned(user, other))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Number not found"));

        assertThatThrownBy(() -> service.create(user, new CreateOrderCommand(other, plan.id(), key, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void getReturnsOnlyOwnOrders() {
        UUID id = UUID.randomUUID();
        when(orders.findByIdAndUserId(id, user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(user, id))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
