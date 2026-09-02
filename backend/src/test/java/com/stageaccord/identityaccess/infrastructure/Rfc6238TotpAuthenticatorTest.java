package com.stageaccord.identityaccess.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class Rfc6238TotpAuthenticatorTest {
    private final Rfc6238TotpAuthenticator authenticator = new Rfc6238TotpAuthenticator(new SecureRandom());

    @Test
    void verifiesRfc6238VectorUsingTheConfiguredSixDigits() {
        String secret = Rfc6238TotpAuthenticator.encodeBase32(
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII));

        assertThat(authenticator.verify(secret, "287082", Instant.ofEpochSecond(59))).isTrue();
        assertThat(authenticator.verify(secret, "287083", Instant.ofEpochSecond(59))).isFalse();
    }

    @Test
    void acceptsOnlySixDigitsWithinOneAdjacentTimeStep() {
        String secret = Rfc6238TotpAuthenticator.encodeBase32(
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII));

        assertThat(authenticator.verify(secret, "287082", Instant.ofEpochSecond(89))).isTrue();
        assertThat(authenticator.verify(secret, "287082", Instant.ofEpochSecond(120))).isFalse();
        assertThat(authenticator.verify(secret, "not-a-code", Instant.ofEpochSecond(59))).isFalse();
    }

    @Test
    void issuesA160BitBase32Secret() {
        String secret = authenticator.issueSecret();

        assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
        assertThat(authenticator.issueSecret()).isNotEqualTo(secret);
    }
}
