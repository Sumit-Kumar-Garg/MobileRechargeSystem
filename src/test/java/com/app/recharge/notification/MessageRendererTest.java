package com.app.recharge.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.recharge.support.TestData;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageRendererTest {

    private final MessageRenderer renderer = new MessageRenderer();
    private final UUID user = UUID.randomUUID();

    @Test
    void germanCompletedMessage() {
        NotificationContent c = renderer.render(TestData.event("OrderCompleted", user), Locale.GERMANY);

        assertThat(c.title()).isEqualTo("Aufladung erfolgreich");
        assertThat(c.body()).contains("Prepaid M", "+49 *** ***4567", "28 Tage", "15,00");
    }

    @Test
    void englishCompletedMessage() {
        NotificationContent c = renderer.render(TestData.event("OrderCompleted", user), Locale.US);

        assertThat(c.title()).isEqualTo("Recharge successful");
        assertThat(c.body()).contains("Prepaid M", "+49 *** ***4567", "28 days", "15.00");
    }

    @Test
    void refundMessagesExplainWhatHappened() {
        assertThat(renderer.render(TestData.event("OrderRefunded", user), Locale.US).body())
                .contains("could not activate", "refunded");
        assertThat(renderer.render(TestData.event("OrderRefunded", user), Locale.GERMANY).body())
                .contains("nicht aktivieren", "erstattet");
    }

    @Test
    void unknownLanguageFallsBackToEnglish() {
        assertThat(renderer.render(TestData.event("OrderCompleted", user), Locale.FRANCE).title())
                .isEqualTo("Recharge successful");
    }

    @Test
    void messagesNeverContainTheFullNumber() {
        NotificationContent c = renderer.render(TestData.event("OrderCompleted", user), Locale.GERMANY);
        assertThat(c.body() + c.emailBody() + c.title()).doesNotContain("1701234567");
    }

    @Test
    void eventsWithoutATemplateAreRejected() {
        assertThatThrownBy(() -> renderer.render(TestData.event("OrderCreated", user), Locale.US))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatsAmountsUsingTheCurrencyFractionDigits() {
        assertThat(MessageRenderer.formatAmount(3500, "PLN", Locale.forLanguageTag("pl-PL"))).contains("35,00");
        assertThat(MessageRenderer.formatAmount(1000, "EUR", Locale.US)).isEqualTo("€10.00");
    }
}
