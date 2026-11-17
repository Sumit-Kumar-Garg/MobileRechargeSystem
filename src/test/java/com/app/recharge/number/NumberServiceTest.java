package com.app.recharge.number;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import com.app.recharge.crypto.CryptoProperties;
import com.app.recharge.crypto.PiiCrypto;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
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
class NumberServiceTest {

    @Mock SubscriberNumberRepository repository;
    PiiCrypto crypto;
    NumberService service;
    final UUID user = UUID.randomUUID();

    private static String key(char c) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) c);
        return Base64.getEncoder().encodeToString(b);
    }

    @BeforeEach
    void setUp() {
        crypto = new PiiCrypto(new CryptoProperties(key('a'), key('b')));
        service = new NumberService(repository, crypto, NumberProperties.defaults(), Clock.systemUTC());
        when(repository.findByUserIdAndMsisdnHash(any(), any())).thenReturn(Optional.empty());
        when(repository.countByUserId(user)).thenReturn(0L);
        when(repository.saveAndFlush(any(SubscriberNumber.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void linksNewNumberEncryptedAndMasked() {
        NumberView view = service.link(user, "+49 170 1234567");

        assertThat(view.msisdnMasked()).isEqualTo("+49 *** ***4567");
        assertThat(view.market()).isEqualTo("DE");
        var captor = org.mockito.ArgumentCaptor.forClass(SubscriberNumber.class);
        verify(repository).saveAndFlush(captor.capture());
        SubscriberNumber saved = captor.getValue();
        assertThat(crypto.decrypt(saved.getMsisdnEnc())).isEqualTo("+491701234567");
        assertThat(saved.getMsisdnHash()).hasSize(64).doesNotContain("491701234567");
    }

    @Test
    void linkingTheSameNumberAgainReturnsTheExistingOne() {
        SubscriberNumber existing = TestDataNumber.of(user);
        when(repository.findByUserIdAndMsisdnHash(any(), any())).thenReturn(Optional.of(existing));

        NumberView view = service.link(user, "+491701234567");

        assertThat(view.id()).isEqualTo(existing.getId());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void enforcesPerUserLimit() {
        when(repository.countByUserId(user)).thenReturn((long) NumberProperties.defaults().maxPerUser());

        assertThatThrownBy(() -> service.link(user, "+491701234567"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NUMBER_LIMIT_EXCEEDED));
    }

    @Test
    void rejectsUnsupportedCountry() {
        assertThatThrownBy(() -> service.link(user, "+14155550123")).isInstanceOf(BusinessException.class);
    }

    @Test
    void notOwnedLooksLikeNotFound() {
        UUID numberId = UUID.randomUUID();
        when(repository.findByIdAndUserId(numberId, user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireOwned(user, numberId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void plainMsisdnDecryptsForInternalUse() {
        SubscriberNumber n = new SubscriberNumber(user, crypto.encrypt("+491701234567"), "h", "+49 *** ***4567", "DE",
                java.time.Instant.now());
        when(repository.findById(n.getId())).thenReturn(Optional.of(n));

        assertThat(service.plainMsisdn(n.getId())).isEqualTo("+491701234567");
    }

    /** tiny local factory to keep the test self-contained */
    static final class TestDataNumber {
        static SubscriberNumber of(UUID user) {
            return new SubscriberNumber(user, new byte[] {1}, "h", "+49 *** ***4567", "DE", java.time.Instant.now());
        }
    }
}
