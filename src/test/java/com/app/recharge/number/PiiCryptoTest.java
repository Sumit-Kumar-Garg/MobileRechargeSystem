package com.app.recharge.number;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.recharge.crypto.CryptoProperties;
import com.app.recharge.crypto.PiiCrypto;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PiiCryptoTest {

    private static String key(int bytes, char fill) {
        byte[] b = new byte[bytes];
        Arrays.fill(b, (byte) fill);
        return Base64.getEncoder().encodeToString(b);
    }

    private final PiiCrypto crypto = new PiiCrypto(new CryptoProperties(key(32, 'a'), key(32, 'b')));

    @Test
    void encryptsAndDecryptsRoundTrip() {
        byte[] enc = crypto.encrypt("+491701234567");
        assertThat(new String(enc)).doesNotContain("491701234567");
        assertThat(crypto.decrypt(enc)).isEqualTo("+491701234567");
    }

    @Test
    void sameInputGivesDifferentCiphertexts() {
        assertThat(crypto.encrypt("x")).isNotEqualTo(crypto.encrypt("x"));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        byte[] enc = crypto.encrypt("secret");
        enc[enc.length - 1] ^= 1;
        assertThatThrownBy(() -> crypto.decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dataEncryptedWithAnOldKeyIsStillReadableAfterRotation() {
        byte[] oldCiphertext = crypto.encrypt("+491701234567");            // key id 1, key 'a'
        PiiCrypto rotated = new PiiCrypto(new CryptoProperties(key(32, 'z'), key(32, 'b'), 2,
                java.util.Map.of(1, key(32, 'a'))));

        assertThat(rotated.decrypt(oldCiphertext)).isEqualTo("+491701234567");
        byte[] fresh = rotated.encrypt("new");
        assertThat(fresh[0]).isEqualTo((byte) 2);                          // new data uses the new key id
        assertThat(rotated.decrypt(fresh)).isEqualTo("new");
        assertThatThrownBy(() -> crypto.decrypt(fresh)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anOldKeyThatWasRemovedCanNoLongerDecrypt() {
        byte[] oldCiphertext = crypto.encrypt("x");
        PiiCrypto withoutOld = new PiiCrypto(new CryptoProperties(key(32, 'z'), key(32, 'b'), 2, java.util.Map.of()));
        assertThatThrownBy(() -> withoutOld.decrypt(oldCiphertext)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hashIsDeterministicAndKeyed() {
        PiiCrypto other = new PiiCrypto(new CryptoProperties(key(32, 'a'), key(32, 'c')));
        assertThat(crypto.hash("v")).isEqualTo(crypto.hash("v")).hasSize(64);
        assertThat(crypto.hash("v")).isNotEqualTo(crypto.hash("w")).isNotEqualTo(other.hash("v"));
    }

    @Test
    void rejectsWeakOrMissingKeys() {
        assertThatThrownBy(() -> new PiiCrypto(new CryptoProperties("", key(32, 'b'))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PiiCrypto(new CryptoProperties(key(16, 'a'), key(32, 'b'))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PiiCrypto(new CryptoProperties(key(32, 'a'), key(8, 'b'))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PiiCrypto(new CryptoProperties("not base64 !!", key(32, 'b'))))
                .isInstanceOf(IllegalStateException.class);
    }
}
