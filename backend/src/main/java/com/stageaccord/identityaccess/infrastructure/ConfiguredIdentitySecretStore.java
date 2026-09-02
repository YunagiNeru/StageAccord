package com.stageaccord.identityaccess.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.stageaccord.identityaccess.application.IdentitySecretStore;
import com.stageaccord.identityaccess.application.IssuedToken;
import com.stageaccord.identityaccess.application.ProtectedValue;

@Component
public final class ConfiguredIdentitySecretStore implements IdentitySecretStore {
    private static final String DIGEST_KEY_ID = "identity-v1";
    private static final String FIELD_KEY_ID = "field-v1";
    private static final String ALGORITHM = "AES-256-GCM";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random;
    private final SecretKeySpec digestKey;
    private final SecretKeySpec fieldKey;
    private final PasswordEncoder passwordEncoder;

    public ConfiguredIdentitySecretStore(
            @Value("${stage-accord.security.session-hmac-key}") String digestSecret,
            @Value("${stage-accord.security.field-encryption-key}") String fieldSecret) {
        this(new SecureRandom(), derive(digestSecret, "identity-digest", "HmacSHA256"),
                derive(fieldSecret, "identity-field", "AES"),
                Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
    }

    ConfiguredIdentitySecretStore(SecureRandom random, SecretKeySpec digestKey,
            SecretKeySpec fieldKey, PasswordEncoder passwordEncoder) {
        this.random = random;
        this.digestKey = digestKey;
        this.fieldKey = fieldKey;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public byte[] emailDigest(String email) {
        return digest(email.strip().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public byte[] tokenDigest(String token) {
        return digest(token);
    }

    private byte[] digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(digestKey);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("identity digest unavailable", exception);
        }
    }

    @Override
    public ProtectedValue protect(String value) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, fieldKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return new ProtectedValue(FIELD_KEY_ID, ALGORITHM, encode(nonce), encode(ciphertext));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("field protection unavailable", exception);
        }
    }

    @Override
    public String reveal(ProtectedValue value) {
        if (!FIELD_KEY_ID.equals(value.keyId()) || !ALGORITHM.equals(value.algorithm())) {
            throw new IllegalArgumentException("unsupported protected value");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, fieldKey,
                    new GCMParameterSpec(GCM_TAG_BITS, Base64.getUrlDecoder().decode(value.nonce())));
            return new String(cipher.doFinal(Base64.getUrlDecoder().decode(value.ciphertext())), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("protected value could not be verified", exception);
        }
    }

    @Override
    public String hashPassword(String password) {
        return passwordEncoder.encode(password);
    }

    @Override
    public boolean passwordMatches(String password, String encodedPassword) {
        return passwordEncoder.matches(password, encodedPassword);
    }

    @Override
    public IssuedToken issueToken() {
        byte[] token = new byte[TOKEN_BYTES];
        random.nextBytes(token);
        String plaintext = encode(token);
        return new IssuedToken(plaintext, tokenDigest(plaintext), DIGEST_KEY_ID);
    }

    private static SecretKeySpec derive(String source, String purpose, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(purpose.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return new SecretKeySpec(digest.digest(source.getBytes(StandardCharsets.UTF_8)), algorithm);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("key derivation unavailable", exception);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
