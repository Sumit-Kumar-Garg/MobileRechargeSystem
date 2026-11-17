package com.app.recharge.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.number.NumberService;
import com.app.recharge.order.OrderEventPayload;
import com.app.recharge.ratelimit.InMemoryRateLimiter;
import com.app.recharge.support.MutableClock;
import com.app.recharge.support.TestData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    @Mock NotificationRepository repo;
    @Mock PreferenceService preferences;
    @Mock DeviceService devices;
    @Mock NumberService numbers;

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T10:00:00Z"));
    final InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);
    final Map<String, Notification> store = new HashMap<>();
    final UUID user = UUID.randomUUID();
    NotificationChannel push;
    NotificationChannel sms;
    NotificationChannel email;
    NotificationService service;
    OrderEventPayload event;

    private NotificationChannel channel(ChannelType type) {
        NotificationChannel c = mock(NotificationChannel.class);
        when(c.type()).thenReturn(type);
        when(c.send(any(), any())).thenReturn(type.name().toLowerCase() + "-ref");
        return c;
    }

    private void prefs(boolean pushOn, boolean smsOn, boolean emailOn, String address) {
        when(preferences.get(user)).thenReturn(
                new PreferenceService.Preferences(pushOn, smsOn, emailOn, "de-DE", address));
    }

    @BeforeEach
    void setUp() {
        push = channel(ChannelType.PUSH);
        sms = channel(ChannelType.SMS);
        email = channel(ChannelType.EMAIL);
        service = new NotificationService(repo, preferences, devices, numbers, new MessageRenderer(), limiter,
                new RechargeMetrics(new SimpleMeterRegistry()), NotificationProperties.defaults(), clock,
                List.of(push, sms, email));
        event = TestData.event("OrderCompleted", user);

        when(repo.findByOrderIdAndEventTypeAndChannel(any(), any(), any()))
                .thenAnswer(i -> Optional.ofNullable(store.get((String) i.getArgument(2))));
        when(repo.saveAndFlush(any(Notification.class))).thenAnswer(i -> {
            Notification n = i.getArgument(0);
            store.put(n.getChannel(), n);
            return n;
        });
        when(repo.save(any(Notification.class))).thenAnswer(i -> {
            Notification n = i.getArgument(0);
            store.put(n.getChannel(), n);
            return n;
        });
        when(repo.existsByOrderIdAndEventTypeAndChannelInAndStatus(any(), any(), any(), any()))
                .thenAnswer(i -> store.values().stream().anyMatch(n ->
                        ("PUSH".equals(n.getChannel()) || "SMS".equals(n.getChannel()))
                                && Notification.SENT.equals(n.getStatus())));
        when(devices.activeTokens(user)).thenReturn(List.of("fcm_token_abcdefghijklmnop"));
        when(numbers.plainMsisdn(event.numberId())).thenReturn("+491701234567");
        prefs(true, true, true, null);
    }

    @Test
    void pushIsPreferredAndSmsIsNotUsedWhenPushWorks() {
        service.handle(event);

        verify(push).send(any(), any());
        verify(sms, never()).send(any(), any());
        assertThat(store.get("PUSH").getStatus()).isEqualTo(Notification.SENT);
    }

    @Test
    void smsFallsBackWhenTheUserHasNoDevice() {
        when(devices.activeTokens(user)).thenReturn(List.of());

        service.handle(event);

        verify(push, never()).send(any(), any());
        verify(sms).send(any(), any());
        assertThat(store.get("SMS").getStatus()).isEqualTo(Notification.SENT);
    }

    @Test
    void smsFallsBackWhenPushFails() {
        when(push.send(any(), any())).thenThrow(new ChannelException("unregistered", "token gone"));

        assertThatCode(() -> service.handle(event)).doesNotThrowAnyException();

        assertThat(store.get("PUSH").getStatus()).isEqualTo(Notification.FAILED);
        assertThat(store.get("PUSH").getFailureCode()).isEqualTo("unregistered");
        assertThat(store.get("SMS").getStatus()).isEqualTo(Notification.SENT);
    }

    @Test
    void smsGoesToTheRechargedNumber() {
        when(devices.activeTokens(user)).thenReturn(List.of());

        service.handle(event);

        var captor = org.mockito.ArgumentCaptor.forClass(Recipient.class);
        verify(sms).send(captor.capture(), any());
        assertThat(captor.getValue().msisdn()).isEqualTo("+491701234567");
    }

    @Test
    void throwsWhenNothingReachedTheUserSoKafkaRetries() {
        when(push.send(any(), any())).thenThrow(new ChannelException("down", "push down"));
        when(sms.send(any(), any())).thenThrow(new ChannelException("down", "sms down"));

        assertThatThrownBy(() -> service.handle(event)).isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    void processingTheSameEventTwiceSendsOnlyOnce() {
        service.handle(event);
        service.handle(event);

        verify(push, times(1)).send(any(), any());
        verify(sms, never()).send(any(), any());
    }

    @Test
    void aRetryAfterPartialFailureDoesNotResendWhatAlreadyWorked() {
        prefs(true, true, true, "user@example.com");
        when(email.send(any(), any())).thenThrow(new ChannelException("smtp", "mail down")).thenReturn("email-ref");

        assertThatThrownBy(() -> service.handle(event)).isInstanceOf(NotificationDeliveryException.class);
        service.handle(event);                       // the Kafka retry

        verify(push, times(1)).send(any(), any());   // push was already delivered
        verify(email, times(2)).send(any(), any());  // email retried until it worked
        assertThat(store.get("EMAIL").getStatus()).isEqualTo(Notification.SENT);
    }

    @Test
    void emailIsAnIndependentReceipt() {
        prefs(true, true, true, "user@example.com");

        service.handle(event);

        verify(push).send(any(), any());
        verify(email).send(any(), any());
    }

    @Test
    void emailNeedsAnAddress() {
        prefs(true, true, true, null);
        service.handle(event);
        verify(email, never()).send(any(), any());
    }

    @Test
    void disabledChannelsAreNotUsed() {
        prefs(false, false, false, "user@example.com");

        assertThatCode(() -> service.handle(event)).doesNotThrowAnyException();

        verify(push, never()).send(any(), any());
        verify(sms, never()).send(any(), any());
        verify(email, never()).send(any(), any());
    }

    @Test
    void dailySmsCapStopsFurtherSmsWithoutEndlessRetries() {
        when(devices.activeTokens(user)).thenReturn(List.of());
        for (int i = 0; i < NotificationProperties.defaults().maxSmsPerDay(); i++) {
            limiter.hit("sms:" + user, NotificationProperties.defaults().maxSmsPerDay(), Duration.ofDays(1));
        }

        assertThatCode(() -> service.handle(event)).doesNotThrowAnyException();

        verify(sms, never()).send(any(), any());
    }

    @Test
    void eventsThatAreNotUserFacingAreIgnored() {
        service.handle(TestData.event("OrderPaid", user));
        service.handle(TestData.event("OrderCreated", user));

        verify(push, never()).send(any(), any());
        assertThat(store).isEmpty();
    }

    @Test
    void refundEventsAreDeliveredToo() {
        OrderEventPayload refund = TestData.event("OrderRefunded", user);

        service.handle(refund);

        verify(push).send(any(), any());
    }
}
