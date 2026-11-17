package com.app.recharge.provisioning;

import com.app.recharge.payment.RefundService;
import com.app.recharge.provisioning.spi.OcsUnavailableException;
import com.app.recharge.provisioning.spi.OcsResult;
import com.app.recharge.provisioning.spi.OcsRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.common.Topics;
import com.app.recharge.number.NumberService;
import com.app.recharge.order.OrderEvent;
import com.app.recharge.order.OrderEventRepository;
import com.app.recharge.order.OrderStore;
import com.app.recharge.order.OrderStatus;
import com.app.recharge.order.OrderTransitions;
import com.app.recharge.order.RechargeOrder;
import com.app.recharge.outbox.OutboxWriter;
import com.app.recharge.support.MutableClock;
import com.app.recharge.support.NoOpTransactionManager;
import com.app.recharge.support.TestData;
import com.app.recharge.telemetry.JourneyRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
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
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProvisioningServiceTest {

    @Mock OrderStore orders;
    @Mock OrderEventRepository eventRepo;
    @Mock OutboxWriter outbox;
    @Mock JourneyRecorder journey;
    @Mock NumberService numbers;
    @Mock OcsGateway ocs;
    @Mock RefundService refunds;

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T10:00:00Z"));
    ProvisioningService service;
    RechargeOrder order;

    @BeforeEach
    void setUp() {
        RechargeMetrics metrics = new RechargeMetrics(new SimpleMeterRegistry());
        OrderTransitions transitions = new OrderTransitions(eventRepo, outbox, journey, metrics, clock);
        service = new ProvisioningService(orders, transitions, numbers, ocs, refunds,
                new TransactionTemplate(new NoOpTransactionManager()),
                new ProvisioningProperties(3, Duration.ofMinutes(2), Duration.ofSeconds(5)), metrics, clock);

        order = TestData.order(UUID.randomUUID(), TestData.plan("M-28", 1500, 10000), clock.instant());
        order.transitionTo(OrderStatus.PAYMENT_PENDING, clock.instant());
        order.transitionTo(OrderStatus.PAID, clock.instant());

        when(orders.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        when(numbers.plainMsisdn(order.getNumberId())).thenReturn("+491701234567");
        when(eventRepo.save(any(OrderEvent.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void successCompletesTheOrderAndEmitsOrderCompleted() {
        when(ocs.provision(any(), any())).thenReturn(OcsResult.success("ocs_1"));

        service.provisionOnce(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getCompletedAt()).isNotNull();
        assertThat(order.getProvisioningAttempts()).isEqualTo(1);
        verify(outbox).write(eq(Topics.ORDER_EVENTS), eq(order.getId().toString()), eq("OrderCompleted"), any());
    }

    @Test
    void ocsReceivesThePlainNumberAndOrderIdAsReference() {
        when(ocs.provision(any(), any())).thenReturn(OcsResult.success("ocs_1"));

        service.provisionOnce(order.getId());

        var captor = org.mockito.ArgumentCaptor.forClass(OcsRequest.class);
        verify(ocs).provision(any(), captor.capture());
        assertThat(captor.getValue().msisdn()).isEqualTo("+491701234567");
        assertThat(captor.getValue().referenceId()).isEqualTo(order.getId().toString());
        assertThat(captor.getValue().planCode()).isEqualTo("M-28");
    }

    @Test
    void outageMarksProvisioningFailedForRetry() {
        when(ocs.provision(any(), any())).thenThrow(new OcsUnavailableException("down"));

        service.provisionOnce(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PROVISIONING_FAILED);
        assertThat(order.getFailureCode()).isEqualTo("ocs_unavailable");
        assertThat(order.getProvisioningAttempts()).isEqualTo(1);
        verify(refunds, never()).refund(any());
    }

    @Test
    void aDefinitiveRejectionGoesStraightToARefundWithoutRetries() {
        when(ocs.provision(any(), any())).thenReturn(OcsResult.rejected("number_not_eligible"));

        service.provisionOnce(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUND_INITIATED);
        assertThat(order.getFailureCode()).isEqualTo("number_not_eligible");
        assertThat(order.getProvisioningAttempts()).isEqualTo(1);
        verify(ocs, org.mockito.Mockito.times(1)).provision(any(), any());
        verify(refunds).refund(order.getId());
    }

    @Test
    void theReferenceIsCancelledAtTheBillingSystemBeforeRefunding() {
        when(ocs.provision(any(), any())).thenThrow(new OcsUnavailableException("down"));

        service.provisionOnce(order.getId());
        service.provisionOnce(order.getId());
        service.provisionOnce(order.getId());

        var inOrder = org.mockito.Mockito.inOrder(ocs, refunds);
        inOrder.verify(ocs).cancel("operator", order.getId().toString());
        inOrder.verify(refunds).refund(order.getId());
    }

    @Test
    void theCustomerIsRefundedEvenWhenTheCancellationCannotBeConfirmed() {
        org.mockito.Mockito.doThrow(new OcsUnavailableException("down")).when(ocs).cancel(any(), any());

        service.compensate(order.getId());

        verify(refunds).refund(order.getId());
    }

    @Test
    void retrySucceedsAfterTransientFailure() {
        when(ocs.provision(any(), any())).thenThrow(new OcsUnavailableException("down"))
                .thenReturn(OcsResult.success("ocs_2"));

        service.provisionOnce(order.getId());
        service.provisionOnce(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getProvisioningAttempts()).isEqualTo(2);
        assertThat(order.getFailureCode()).isNull();
    }

    @Test
    void exhaustedAttemptsLeadStraightToARefund() {
        when(ocs.provision(any(), any())).thenThrow(new OcsUnavailableException("down"));

        service.provisionOnce(order.getId());
        service.provisionOnce(order.getId());
        service.provisionOnce(order.getId());      // third failure spends the budget

        assertThat(order.getProvisioningAttempts()).isEqualTo(3);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUND_INITIATED);
        verify(refunds).refund(order.getId());
    }

    @Test
    void staleProvisioningAttemptIsMarkedFailedSoItCanRetry() {
        order.transitionTo(OrderStatus.PROVISIONING, clock.instant());
        clock.advance(Duration.ofMinutes(3));

        service.provisionOnce(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PROVISIONING_FAILED);
        verify(ocs, never()).provision(any(), any());
    }

    @Test
    void freshProvisioningAttemptIsLeftAlone() {
        order.transitionTo(OrderStatus.PROVISIONING, clock.instant());
        clock.advance(Duration.ofSeconds(30));

        service.provisionOnce(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PROVISIONING);
        verify(ocs, never()).provision(any(), any());
    }

    @Test
    void ordersInOtherStatesAreIgnored() {
        order.transitionTo(OrderStatus.PROVISIONING, clock.instant());
        order.transitionTo(OrderStatus.COMPLETED, clock.instant());

        service.provisionOnce(order.getId());

        verify(ocs, never()).provision(any(), any());
        assertThat(order.getProvisioningAttempts()).isZero();
    }

    @Test
    void unknownOrderIsIgnored() {
        service.provisionOnce(UUID.randomUUID());
        verify(ocs, never()).provision(any(), any());
    }
}
