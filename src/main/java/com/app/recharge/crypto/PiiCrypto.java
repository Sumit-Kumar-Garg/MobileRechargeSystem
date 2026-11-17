package com.app.recharge.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Field-level protection for PII: AES-256-GCM for values we must read back (phone number, email, push token)
 * and HMAC-SHA256 for values we only need to look up or pseudonymize. Keys come from the environment / a vault.
 *
 * Ciphertext layout: [1 byte key id][12 byte IV][ciphertext + tag]. The key id lets old data be read after
 * the encryption key has been rotated. (The HMAC key cannot be rotated the same way because stored hashes
 * are used for lookups; changing it needs a re-hash migration.)
 */
@Component
public class PiiCrypto {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final int currentKeyId;
    private final Map<Integer, SecretKeySpec> aesKeys = new HashMap<>();
    private final SecretKeySpec hmacKey;
    private final SecureRandom random = new SecureRandom();

    /**
     * Loads and checks the keys at startup: current and previous AES keys must be 32 bytes and use distinct ids, the
     * HMAC key at least 32 bytes. Fails fast on bad configuration.
     */
    public PiiCrypto(CryptoProperties props) {
        if (props.aesKeyId() < 1 || props.aesKeyId() > 255) {
            throw new IllegalStateException("app.crypto.aes-key-id must be between 1 and 255");
        }
        this.currentKeyId = props.aesKeyId();
        aesKeys.put(currentKeyId, aesKey(props.aesKey(), "app.crypto.aes-key"));
        if (props.previousAesKeys() != null) {
            props.previousAesKeys().forEach((id, key) -> {
                if (id == currentKeyId) {
                    throw new IllegalStateException("app.crypto.previous-aes-keys reuses the current key id " + id);
                }
                aesKeys.put(id, aesKey(key, "app.crypto.previous-aes-keys." + id));
            });
        }
        byte[] hmac = decode(props.hmacKey(), "app.crypto.hmac-key");
        if (hmac.length < 32) {
            throw new IllegalStateException("app.crypto.hmac-key must be base64 of at least 32 bytes");
        }
        this.hmacKey = new SecretKeySpec(hmac, "HmacSHA256");
    }

    /** Encrypts a value with the current AES key and a fresh random IV; the key id is stored in the first byte. */
    public byte[] encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKeys.get(currentKeyId), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(UTF_8));
            byte[] out = new byte[1 + iv.length + ct.length];
            out[0] = (byte) currentKeyId;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a value written by {@link #encrypt}, using the key named by its first byte, so data encrypted with a
     * rotated-out key can still be read.
     */
    public String decrypt(byte[] data) {
        try {
            SecretKeySpec key = aesKeys.get(data[0] & 0xFF);
            if (key == null) {
                throw new IllegalStateException("No key configured for key id " + (data[0] & 0xFF));
            }
            byte[] iv = Arrays.copyOfRange(data, 1, 1 + IV_LENGTH);
            byte[] ct = Arrays.copyOfRange(data, 1 + IV_LENGTH, data.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    /** Deterministic keyed hash (hex). Same input and key give the same output, so it is usable for lookups. */
    public String hash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Hashing failed", e);
        }
    }

    /** Decodes a base64 AES-256 key and checks that it is exactly 32 bytes. */
    private static SecretKeySpec aesKey(String base64, String property) {
        byte[] key = decode(base64, property);
        if (key.length != 32) {
            throw new IllegalStateException(property + " must be base64 of exactly 32 bytes");
        }
        return new SecretKeySpec(key, "AES");
    }

    /** Decodes base64, naming the offending property in the error if it is invalid. */
    private static byte[] decode(String base64, String property) {
        try {
            return Base64.getDecoder().decode(base64 == null ? "" : base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(property + " is not valid base64", e);
        }
    }
}
