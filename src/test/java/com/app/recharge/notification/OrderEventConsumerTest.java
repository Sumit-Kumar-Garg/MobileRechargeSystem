package com.app.recharge.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.order.OrderEventPayload;
import com.app.recharge.support.TestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderEventConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final NotificationService notifications = mock(NotificationService.class);
    private final OrderEventConsumer consumer =
            new OrderEventConsumer(notifications, mapper, new RechargeMetrics(new SimpleMeterRegistry()));

    @Test
    void parsesTheEventAndHandsItToTheNotificationService() throws Exception {
        OrderEventPayload event = TestData.event("OrderCompleted", UUID.randomUUID());

        consumer.process(mapper.writeValueAsString(event));

        ArgumentCaptor<OrderEventPayload> captor = ArgumentCaptor.forClass(OrderEventPayload.class);
        verify(notifications).handle(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(event);
    }

    @Test
    void ignoresUnknownFieldsFromNewerProducers() throws Exception {
        String json = mapper.writeValueAsString(TestData.event("OrderCompleted", UUID.randomUUID()))
                .replaceFirst("\\{", "{\"futureField\":\"x\",");

        consumer.process(json);

        verify(notifications).handle(any());
    }

    @Test
    void unparseableMessagesAreMarkedPoisonSoTheySkipRetries() {
        assertThatThrownBy(() -> consumer.process("{not json"))
                .isInstanceOf(PoisonMessageException.class);
        verify(notifications, never()).handle(any());
    }

    @Test
    void deliveryFailuresPropagateSoTheRetryTopicsTakeOver() throws Exception {
        doThrow(new NotificationDeliveryException("not delivered")).when(notifications).handle(any());

        assertThatThrownBy(() -> consumer.process(
                mapper.writeValueAsString(TestData.event("OrderCompleted", UUID.randomUUID()))))
                .isInstanceOf(NotificationDeliveryException.class);
    }
}
