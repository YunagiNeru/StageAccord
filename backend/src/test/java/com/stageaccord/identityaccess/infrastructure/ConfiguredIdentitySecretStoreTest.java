package com.stageaccord.identityaccess.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class ConfiguredIdentitySecretStoreTest {
    private final ConfiguredIdentitySecretStore secrets = new ConfiguredIdentitySecretStore(
            new SecureRandom(),
            new SecretKeySpec(new byte[32], "HmacSHA256"),
            new SecretKeySpec(new byte[32], "AES"),
            Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());

    @Test
    void protectsSensitiveFieldsWithAuthenticatedEncryption() {
        var first = secrets.protect("person@example.com");
        var second = secrets.protect("person@example.com");

        assertThat(first.ciphertext()).doesNotContain("person@example.com");
        assertThat(second.ciphertext()).isNotEqualTo(first.ciphertext());
        assertThat(secrets.reveal(first)).isEqualTo("person@example.com");
        assertThatThrownBy(() -> secrets.reveal(new com.stageaccord.identityaccess.application.ProtectedValue(
                first.keyId(), first.algorithm(), first.nonce(), first.ciphertext() + "a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hashesPasswordsWithArgon2AndNeverStoresTheOriginal() {
        String encoded = secrets.hashPassword("correct horse battery staple");

        assertThat(encoded).startsWith("$argon2id$").doesNotContain("correct horse");
        assertThat(secrets.passwordMatches("correct horse battery staple", encoded)).isTrue();
        assertThat(secrets.passwordMatches("wrong", encoded)).isFalse();
    }

    @Test
    void issuesOpaque256BitTokensAndStableDigests() {
        var token = secrets.issueToken();

        assertThat(java.util.Base64.getUrlDecoder().decode(token.plaintext())).hasSize(32);
        assertThat(token.digest()).hasSize(32).isEqualTo(secrets.tokenDigest(token.plaintext()));
        assertThat(secrets.issueToken().plaintext()).isNotEqualTo(token.plaintext());
    }

    @Test
    void normalizesOnlyEmailDigestsAndKeepsTokensCaseSensitive() {
        assertThat(secrets.emailDigest(" Person@Example.COM "))
                .isEqualTo(secrets.emailDigest("person@example.com"));
        assertThat(secrets.tokenDigest("CaseSensitive"))
                .isNotEqualTo(secrets.tokenDigest("casesensitive"));
    }
}
