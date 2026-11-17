package com.app.recharge.notification;

import com.app.recharge.number.Msisdn;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Development channels that only log (masked data, never full contact details).
 * Replace with FCM / SMS-gateway / email-provider adapters by implementing NotificationChannel and
 * setting app.notification.provider to something other than "log".
 */
@Configuration
@ConditionalOnProperty(name = "app.notification.provider", havingValue = "log", matchIfMissing = true)
public class LoggingChannels {

    private static final Logger log = LoggerFactory.getLogger(LoggingChannels.class);

    /** Push channel that logs the title and device count. Fails with no_device if the user has no active device. */
    @Bean
    NotificationChannel logPushChannel() {
        return new NotificationChannel() {
            @Override public ChannelType type() { return ChannelType.PUSH; }

            @Override public String send(Recipient r, NotificationContent c) {
                if (r.pushTokens().isEmpty()) {
                    throw new ChannelException("no_device", "No active device");
                }
                log.info("[PUSH] {} device(s): {}", r.pushTokens().size(), c.title());
                return "push-" + UUID.randomUUID();
            }
        };
    }

    /** SMS channel that logs the text with the number masked. Fails with no_number if the number is missing. */
    @Bean
    NotificationChannel logSmsChannel() {
        return new NotificationChannel() {
            @Override public ChannelType type() { return ChannelType.SMS; }

            @Override public String send(Recipient r, NotificationContent c) {
                if (r.msisdn() == null) {
                    throw new ChannelException("no_number", "No number");
                }
                log.info("[SMS] to {}: {}", Msisdn.mask(r.msisdn()), c.body());
                return "sms-" + UUID.randomUUID();
            }
        };
    }

    /** Email channel that logs only the subject. Fails with no_email if no address is set. */
    @Bean
    NotificationChannel logEmailChannel() {
        return new NotificationChannel() {
            @Override public ChannelType type() { return ChannelType.EMAIL; }

            @Override public String send(Recipient r, NotificationContent c) {
                if (r.email() == null) {
                    throw new ChannelException("no_email", "No email");
                }
                log.info("[EMAIL] subject: {}", c.emailSubject());
                return "email-" + UUID.randomUUID();
            }
        };
    }
}
