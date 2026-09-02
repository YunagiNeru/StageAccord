package com.stageaccord.identityaccess.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.stageaccord.sharedkernel.application.AuditRecorder;

import com.stageaccord.identityaccess.application.IdentityApplicationException.Code;

class IdentityAccessServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
    private static final byte[] TOKEN_DIGEST = new byte[32];
    private static final byte[] EMAIL_DIGEST = filledDigest((byte) 3);

    private final IdentityStore store = mock(IdentityStore.class);
    private final IdentitySecretStore secrets = mock(IdentitySecretStore.class);
    private final TotpAuthenticator totp = mock(TotpAuthenticator.class);
    private final VerificationMessageSender messages = mock(VerificationMessageSender.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private IdentityAccessService service;

    @BeforeEach
    void setUp() {
        when(secrets.hashPassword(any())).thenReturn("dummy-argon2");
        when(secrets.emailDigest(any())).thenReturn(EMAIL_DIGEST);
        when(secrets.tokenDigest("token")).thenReturn(TOKEN_DIGEST);
        when(secrets.issueToken()).thenReturn(new IssuedToken("token", TOKEN_DIGEST, "identity-v1"));
        when(secrets.protect(any())).thenAnswer(invocation ->
                new ProtectedValue("field-v1", "AES-256-GCM", "nonce", invocation.getArgument(0)));
        when(secrets.reveal(any())).thenAnswer(invocation ->
                ((ProtectedValue) invocation.getArgument(0)).ciphertext());
        service = new IdentityAccessService(store, secrets, totp, messages, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void issuesEmailChallengeWithoutPersistingThePlaintextToken() {
        service.startEmailVerification("person@example.com");

        ArgumentCaptor<AuthChallenge> challenge = ArgumentCaptor.forClass(AuthChallenge.class);
        verify(store).createChallenge(challenge.capture());
        assertThat(challenge.getValue().tokenDigest()).isEqualTo(TOKEN_DIGEST);
        assertThat(challenge.getValue().protectedSubject().ciphertext()).isEqualTo("person@example.com");
        verify(messages).sendEmailVerification(eq("person@example.com"),
                eq(challenge.getValue().id()), eq("token"));
    }

    @Test
    void consumesAValidEmailChallengeAndRejectsAnInvalidToken() {
        UUID id = UUID.randomUUID();
        when(store.lockChallenge(id)).thenReturn(Optional.of(emailChallenge(id, null, null)));

        service.completeEmailVerification(id, "token");
        verify(store).consumeChallenge(id, NOW);

        when(secrets.tokenDigest("wrong")).thenReturn(filledDigest((byte) 9));
        assertThatThrownBy(() -> service.completeEmailVerification(id, "wrong"))
                .isInstanceOfSatisfying(IdentityApplicationException.class,
                        failure -> assertThat(failure.code()).isEqualTo(Code.INVALID_CHALLENGE));
    }

    @Test
    void registersOnlyAgainstTheConsumedEmailBoundChallenge() {
        UUID id = UUID.randomUUID();
        when(store.lockChallenge(id)).thenReturn(Optional.of(emailChallenge(id, NOW.minusSeconds(1), null)));

        UUID accountId = service.registerCreator(id, "token", "person@example.com",
                "a sufficiently long password");

        verify(store).createAccount(eq(accountId), eq(EMAIL_DIGEST), any(), eq("dummy-argon2"), eq(NOW));
        verify(store).attachChallengeToAccount(id, accountId);

        when(secrets.emailDigest("other@example.com")).thenReturn(new byte[32]);
        assertThatThrownBy(() -> service.registerCreator(id, "token", "other@example.com",
                "a sufficiently long password"))
                .isInstanceOfSatisfying(IdentityApplicationException.class,
                        failure -> assertThat(failure.code()).isEqualTo(Code.INVALID_CHALLENGE));
    }

    @Test
    void consumesFailedTotpEnrollmentAndIssuesRecoveryCodesOnlyOnceOnSuccess() {
        UUID accountId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID enrollmentId = UUID.randomUUID();
        ProtectedValue credentialReference = new ProtectedValue("field-v1", "AES-256-GCM", "nonce",
                credentialId.toString());
        AuthChallenge challenge = new AuthChallenge(enrollmentId, accountId, "totp_enrollment",
                TOKEN_DIGEST, "identity-v1", null, credentialReference, NOW.plusSeconds(60), null);
        when(store.lockChallenge(enrollmentId)).thenReturn(Optional.of(challenge));
        when(store.requirePendingTotp(accountId, credentialId)).thenReturn(
                new ProtectedValue("field-v1", "AES-256-GCM", "nonce", "SECRET"));
        when(totp.verify("SECRET", "000000", NOW)).thenReturn(false);

        assertThatThrownBy(() -> service.confirmTotpEnrollment(enrollmentId, "token", "000000"))
                .isInstanceOfSatisfying(IdentityApplicationException.class,
                        failure -> assertThat(failure.code()).isEqualTo(Code.INVALID_CHALLENGE));
        verify(store).consumeChallenge(enrollmentId, NOW);
        verify(store, never()).activateTotpAndAccount(any(), any());
    }

    @Test
    void authenticatesOnlyAnActivePasswordAndTotpPairAndStoresOnlySessionDigest() {
        UUID accountId = UUID.randomUUID();
        ProtectedValue protectedTotp = new ProtectedValue("field-v1", "AES-256-GCM", "nonce", "SECRET");
        when(store.findAuthentication(EMAIL_DIGEST)).thenReturn(Optional.of(
                new AccountAuthentication(accountId, EMAIL_DIGEST, "active", 4, "encoded-password", protectedTotp)));
        when(secrets.passwordMatches("password", "encoded-password")).thenReturn(true);
        when(totp.verify("SECRET", "123456", NOW)).thenReturn(true);

        IdentityAccessService.IssuedSession issued = service.authenticate(
                "person@example.com", "password", "123456");

        assertThat(issued.token()).isEqualTo("token");
        assertThat(issued.descriptor().tokenDigest()).isEqualTo(TOKEN_DIGEST);
        assertThat(issued.descriptor().authGeneration()).isEqualTo(4);
        verify(store).createSession(issued.descriptor());
    }

    private static AuthChallenge emailChallenge(UUID id, Instant consumedAt, UUID accountId) {
        return new AuthChallenge(id, accountId, "email_verification", TOKEN_DIGEST,
                "identity-v1", EMAIL_DIGEST,
                new ProtectedValue("field-v1", "AES-256-GCM", "nonce", "person@example.com"),
                NOW.plusSeconds(60), consumedAt);
    }

    private static byte[] filledDigest(byte value) {
        byte[] digest = new byte[32];
        java.util.Arrays.fill(digest, value);
        return digest;
    }
}
