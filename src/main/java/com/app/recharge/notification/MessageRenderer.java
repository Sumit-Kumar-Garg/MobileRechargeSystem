package com.app.recharge.notification;

import com.app.recharge.order.OrderEventPayload;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** DE/EN message templates. Only masked data goes into messages (they land on lock screens). */
@Component
public class MessageRenderer {

    /**
     * Builds the push/SMS and email text for an OrderCompleted or OrderRefunded event, in German or English depending
     * on the locale. Throws for other event types.
     */
    public NotificationContent render(OrderEventPayload e, Locale locale) {
        boolean de = "de".equalsIgnoreCase(locale.getLanguage());
        String amount = formatAmount(e.amountMinor(), e.currency(), locale);
        return switch (e.type()) {
            case "OrderCompleted" -> de
                    ? build("Aufladung erfolgreich",
                            "Dein Paket %s (%s) für %s ist für %d Tage aktiv.".formatted(
                                    e.planName(), amount, e.msisdnMasked(), e.validityDays()))
                    : build("Recharge successful",
                            "Your %s pack (%s) for %s is active for %d days.".formatted(
                                    e.planName(), amount, e.msisdnMasked(), e.validityDays()));
            case "OrderRefunded" -> de
                    ? build("Aufladung erstattet",
                            "Wir konnten %s für %s nicht aktivieren. %s wurde erstattet.".formatted(
                                    e.planName(), e.msisdnMasked(), amount))
                    : build("Recharge refunded",
                            "We could not activate %s for %s. %s has been refunded.".formatted(
                                    e.planName(), e.msisdnMasked(), amount));
            default -> throw new IllegalArgumentException("No template for " + e.type());
        };
    }

    /** Content where the email uses the same subject and body as the push/SMS text. */
    private static NotificationContent build(String title, String body) {
        return new NotificationContent(title, body, title, body);
    }

    /** Formats an amount in minor units as currency for the locale, e.g. 1000 EUR in de becomes "10,00 €". */
    static String formatAmount(long amountMinor, String currencyCode, Locale locale) {
        Currency currency = Currency.getInstance(currencyCode);
        NumberFormat format = NumberFormat.getCurrencyInstance(locale);
        format.setCurrency(currency);
        return format.format(BigDecimal.valueOf(amountMinor, currency.getDefaultFractionDigits()));
    }
}
