package com.app.recharge.crypto;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code aesKey} encrypts new data and is identified by {@code aesKeyId} (1-255).
 * {@code previousAesKeys} (id -> base64 key) are still accepted for decryption, which is what makes key
 * rotation possible: deploy a new key and id, keep the old one here until the data has been re-encrypted.
 */
@ConfigurationProperties("app.crypto")
public record CryptoProperties(
        @DefaultValue("") String aesKey,
        @DefaultValue("") String hmacKey,
        @DefaultValue("1") int aesKeyId,
        Map<Integer, String> previousAesKeys) {

    @ConstructorBinding
    public CryptoProperties { }

    /** Convenience constructor for tests: current key id 1 and no previous keys. */
    public CryptoProperties(String aesKey, String hmacKey) {
        this(aesKey, hmacKey, 1, Map.of());
    }
}
