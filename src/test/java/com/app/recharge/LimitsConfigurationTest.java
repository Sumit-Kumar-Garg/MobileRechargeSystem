package com.app.recharge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.recharge.notification.NotificationProperties;
import com.app.recharge.number.NumberProperties;
import com.app.recharge.ratelimit.RateLimitProperties;
import com.app.recharge.reliability.ReconciliationProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

/**
 * Binds the real application.yml (with its ${ENV:default} placeholders) the way Spring Boot does at startup.
 * Proves the configured defaults equal the limits that used to be hard-coded, that an environment variable
 * overrides them, and that invalid values stop the application instead of being used.
 */
class LimitsConfigurationTest {

    private static Binder binder(Map<String, Object> environmentVariables) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test-env", environmentVariables));
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        return Binder.get(env);
    }

    @Test
    void defaultsMatchTheFormerHardCodedLimits() throws IOException {
        Binder b = binder(Map.of());

        RateLimitProperties rl = b.bind("app.ratelimit", RateLimitProperties.class).get();
        assertThat(rl.globalPerMinute()).isEqualTo(120);
        assertThat(rl.policies().get("numbers-link").limit()).isEqualTo(5);
        assertThat(rl.policies().get("numbers-link").window()).isEqualTo(Duration.ofDays(1));
        assertThat(rl.policies().get("orders-create").limit()).isEqualTo(10);
        assertThat(rl.policies().get("telemetry").window()).isEqualTo(Duration.ofMinutes(1));
        assertThat(rl.policies()).hasSize(7);
        assertThat(rl.failedPayments().max()).isEqualTo(5);
        assertThat(rl.failedPayments().window()).isEqualTo(Duration.ofHours(1));
        assertThat(rl.requestSize().maxBody()).isEqualTo(DataSize.ofKilobytes(16));
        assertThat(rl.requestSize().maxTelemetryBody()).isEqualTo(DataSize.ofKilobytes(256));

        assertThat(b.bind("app.numbers", NumberProperties.class).get().maxPerUser()).isEqualTo(3);
        NotificationProperties n = b.bind("app.notification", NotificationProperties.class).get();
        assertThat(n.maxSmsPerDay()).isEqualTo(5);
        assertThat(n.maxActiveDevices()).isEqualTo(5);
        assertThat(b.bind("app.reconciliation", ReconciliationProperties.class).get())
                .isEqualTo(ReconciliationProperties.defaults());
    }

    @Test
    void environmentVariablesOverrideTheDefaults() throws IOException {
        Binder b = binder(Map.of(
                "RATE_LIMIT_NUMBERS_LINK_LIMIT", "7",
                "FAILED_PAYMENTS_MAX", "3",
                "REQUEST_MAX_BODY", "32KB",
                "NUMBERS_MAX_PER_USER", "4",
                "NOTIFICATION_MAX_SMS_PER_DAY", "2",
                "RECONCILIATION_NOT_FOUND_GRACE", "15m"));

        RateLimitProperties rl = b.bind("app.ratelimit", RateLimitProperties.class).get();
        assertThat(rl.policies().get("numbers-link").limit()).isEqualTo(7);
        assertThat(rl.policies().get("numbers-link").window()).isEqualTo(Duration.ofDays(1));   // untouched
        assertThat(rl.failedPayments().max()).isEqualTo(3);
        assertThat(rl.requestSize().maxBody()).isEqualTo(DataSize.ofKilobytes(32));
        assertThat(b.bind("app.numbers", NumberProperties.class).get().maxPerUser()).isEqualTo(4);
        assertThat(b.bind("app.notification", NotificationProperties.class).get().maxSmsPerDay()).isEqualTo(2);
        assertThat(b.bind("app.reconciliation", ReconciliationProperties.class).get().notFoundGrace())
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void invalidValuesStopTheApplication() throws IOException {
        assertThatThrownBy(() -> binder(Map.of("NUMBERS_MAX_PER_USER", "0"))
                .bind("app.numbers", NumberProperties.class)).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> binder(Map.of("FAILED_PAYMENTS_MAX", "0"))
                .bind("app.ratelimit", RateLimitProperties.class)).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> binder(Map.of("RECONCILIATION_NOT_FOUND_GRACE", "-1m"))
                .bind("app.reconciliation", ReconciliationProperties.class)).isInstanceOf(BindException.class);
    }
}
