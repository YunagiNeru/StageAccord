package com.stageaccord.identityaccess.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.stageaccord.identityaccess.application.IdentityApplicationException.Code;
import com.stageaccord.identityaccess.domain.AuthStrength;
import com.stageaccord.identityaccess.domain.SessionState;
import com.stageaccord.sharedkernel.application.AuditRecorder;

@Service
public class IdentityAccessService {
    private static final Duration EMAIL_CHALLENGE_LIFETIME = Duration.ofHours(24);
    private static final Duration ENROLLMENT_LIFETIME = Duration.ofMinutes(5);
    private static final Duration SESSION_LIFETIME = Duration.ofDays(7);
    private static final Duration RECOVERY_CODE_LIFETIME = Duration.ofDays(365);
    private static final String EMAIL_PURPOSE = "email_verification";
    private static final String TOTP_PURPOSE = "totp_enrollment";
    private static final String DIGEST_KEY_ID = "identity-v1";

    private final IdentityStore store;
    private final IdentitySecretStore secrets;
    private final TotpAuthenticator totp;
    private final VerificationMessageSender messages;
    private final AuditRecorder audit;
    private final Clock clock;
    private final String dummyPasswordHash;

    public IdentityAccessService(IdentityStore store, IdentitySecretStore secrets,
            TotpAuthenticator totp, VerificationMessageSender messages, AuditRecorder audit) {
        this(store, secrets, totp, messages, audit, Clock.systemUTC());
    }

    IdentityAccessService(IdentityStore store, IdentitySecretStore secrets,
            TotpAuthenticator totp, VerificationMessageSender messages, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.secrets = secrets;
        this.totp = totp;
        this.messages = messages;
        this.audit = audit;
        this.clock = clock;
        this.dummyPasswordHash = secrets.hashPassword("invalid authentication placeholder");
    }

    @Transactional
    public void startEmailVerification(String email) {
        Instant now = clock.instant();
        IssuedToken token = secrets.issueToken();
        UUID challengeId = UUID.randomUUID();
        store.createChallenge(new AuthChallenge(challengeId, null, EMAIL_PURPOSE,
                token.digest(), token.digestKeyId(), secrets.emailDigest(email), secrets.protect(email),
                now.plus(EMAIL_CHALLENGE_LIFETIME), null));
        audit.recordAllowed("StartEmailVerification", null, null);
        afterCommit(() -> messages.sendEmailVerification(email, challengeId, token.plaintext()));
    }

    @Transactional
    public void completeEmailVerification(UUID challengeId, String token) {
        AuthChallenge challenge = requireChallenge(challengeId, token, EMAIL_PURPOSE, false);
        store.consumeChallenge(challenge.id(), clock.instant());
        audit.recordAllowed("CompleteEmailVerification", null, null);
    }

    @Transactional
    public UUID registerCreator(UUID challengeId, String token, String email, String password) {
        validatePassword(password);
        AuthChallenge challenge = requireChallenge(challengeId, token, EMAIL_PURPOSE, true);
        if (!java.security.MessageDigest.isEqual(challenge.subjectDigest(), secrets.emailDigest(email))) {
            throw IdentityApplicationException.of(Code.INVALID_CHALLENGE);
        }
        UUID accountId = UUID.randomUUID();
        try {
            store.createAccount(accountId, challenge.subjectDigest(), challenge.protectedSubject(),
                    secrets.hashPassword(password), clock.instant());
            store.attachChallengeToAccount(challenge.id(), accountId);
            audit.recordAllowed("RegisterCreator", accountId, null);
            return accountId;
        } catch (DataIntegrityViolationException duplicate) {
            throw IdentityApplicationException.of(Code.CREDENTIAL_ALREADY_REGISTERED);
        }
    }

    @Transactional
    public TotpEnrollment startTotpEnrollment(UUID accountId, UUID emailChallengeId, String emailToken) {
        AuthChallenge emailChallenge = requireChallenge(emailChallengeId, emailToken, EMAIL_PURPOSE, true);
        if (!accountId.equals(emailChallenge.accountId())) {
            throw IdentityApplicationException.of(Code.INVALID_CHALLENGE);
        }
        String secret = totp.issueSecret();
        UUID credentialId = UUID.randomUUID();
        store.createPendingTotp(accountId, credentialId, secrets.protect(secret));
        IssuedToken enrollmentToken = secrets.issueToken();
        UUID enrollmentId = UUID.randomUUID();
        Instant expiresAt = clock.instant().plus(ENROLLMENT_LIFETIME);
        store.createChallenge(new AuthChallenge(enrollmentId, accountId, TOTP_PURPOSE,
                enrollmentToken.digest(), enrollmentToken.digestKeyId(), null,
                secrets.protect(credentialId.toString()), expiresAt, null));
        audit.recordAllowed("StartTotpEnrollment", accountId, null);
        return new TotpEnrollment(enrollmentId, enrollmentToken.plaintext(), secret, expiresAt);
    }

    @Transactional(noRollbackFor = IdentityApplicationException.class)
    public EnrollmentCompletion confirmTotpEnrollment(UUID enrollmentId, String token, String code) {
        AuthChallenge challenge = requireChallenge(enrollmentId, token, TOTP_PURPOSE, false);
        UUID credentialId = UUID.fromString(secrets.reveal(challenge.protectedSubject()));
        ProtectedValue protectedSecret = store.requirePendingTotp(challenge.accountId(), credentialId);
        if (!totp.verify(secrets.reveal(protectedSecret), code, clock.instant())) {
            store.consumeChallenge(challenge.id(), clock.instant());
            throw IdentityApplicationException.of(Code.INVALID_CHALLENGE);
        }
        store.consumeChallenge(challenge.id(), clock.instant());
        store.activateTotpAndAccount(challenge.accountId(), credentialId);
        List<String> recoveryCodes = issueRecoveryCodes(challenge.accountId(), 0);
        IssuedSession session = issueSession(challenge.accountId(), 0, AuthStrength.PASSWORD_TOTP);
        audit.recordAllowed("ConfirmTotpEnrollment", challenge.accountId(), null);
        return new EnrollmentCompletion(recoveryCodes, session);
    }

    @Transactional
    public IssuedSession authenticate(String email, String password, String code) {
        AccountAuthentication account = store.findAuthentication(secrets.emailDigest(email)).orElse(null);
        String encodedPassword = account == null || account.encodedPassword() == null
                ? dummyPasswordHash : account.encodedPassword();
        boolean passwordValid = secrets.passwordMatches(password, encodedPassword);
        boolean totpValid = account != null && account.protectedTotpSecret() != null
                && totp.verify(secrets.reveal(account.protectedTotpSecret()), code, clock.instant());
        if (account == null || !"active".equals(account.status()) || !passwordValid || !totpValid) {
            throw IdentityApplicationException.of(Code.AUTHENTICATION_REQUIRED);
        }
        IssuedSession session = issueSession(account.accountId(), account.authGeneration(), AuthStrength.PASSWORD_TOTP);
        audit.recordAllowed("Authenticate", account.accountId(), null);
        return session;
    }

    @Transactional
    public SessionDescriptor resolveSession(String token) {
        if (token == null || token.isBlank()) {
            throw IdentityApplicationException.of(Code.AUTHENTICATION_REQUIRED);
        }
        SessionDescriptor session = store.findSession(secrets.tokenDigest(token), DIGEST_KEY_ID)
                .orElseThrow(() -> IdentityApplicationException.of(Code.AUTHENTICATION_REQUIRED));
        SessionState state = new SessionState(session.authGeneration(), session.strength(),
                session.authenticatedAt(), session.lastSeenAt(), session.absoluteExpiresAt(),
                session.revokedAt() != null);
        AccountAuthentication account = store.findAuthenticationByAccountId(session.accountId())
                .orElseThrow(() -> IdentityApplicationException.of(Code.AUTHENTICATION_REQUIRED));
        try {
            state.requireValid(account.authGeneration(), clock.instant(), true);
        } catch (com.stageaccord.identityaccess.domain.IdentityRuleViolation failure) {
            throw IdentityApplicationException.of(Code.AUTHENTICATION_REQUIRED);
        }
        store.touchSession(session.id(), clock.instant());
        return session;
    }

    @Transactional
    public List<SessionDescriptor> listSessions(String token) {
        return store.listSessions(resolveFreshSession(token).accountId());
    }

    @Transactional
    public void revokeSession(String token, UUID sessionId) {
        SessionDescriptor current = resolveFreshSession(token);
        store.revokeSession(current.accountId(), sessionId, clock.instant());
        audit.recordAllowed("RevokeSession", current.accountId(), null);
    }

    @Transactional
    public void logout(String token) {
        SessionDescriptor current = resolveSession(token);
        store.revokeSession(current.accountId(), current.id(), clock.instant());
        audit.recordAllowed("Logout", current.accountId(), null);
    }

    private SessionDescriptor resolveFreshSession(String token) {
        SessionDescriptor session = resolveSession(token);
        SessionState state = new SessionState(session.authGeneration(), session.strength(),
                session.authenticatedAt(), session.lastSeenAt(), session.absoluteExpiresAt(),
                session.revokedAt() != null);
        AccountAuthentication account = store.findAuthenticationByAccountId(session.accountId())
                .orElseThrow(() -> IdentityApplicationException.of(Code.AUTHENTICATION_REQUIRED));
        try {
            state.requireFresh(account.authGeneration(), clock.instant(), true);
        } catch (com.stageaccord.identityaccess.domain.IdentityRuleViolation failure) {
            throw IdentityApplicationException.of(Code.AUTH_FRESHNESS_REQUIRED);
        }
        return session;
    }

    private AuthChallenge requireChallenge(UUID challengeId, String token, String purpose,
            boolean requireConsumed) {
        AuthChallenge challenge = store.lockChallenge(challengeId)
                .orElseThrow(() -> IdentityApplicationException.of(Code.INVALID_CHALLENGE));
        if (!purpose.equals(challenge.purpose())
                || !java.security.MessageDigest.isEqual(challenge.tokenDigest(), secrets.tokenDigest(token))
                || !clock.instant().isBefore(challenge.expiresAt())) {
            throw IdentityApplicationException.of(Code.INVALID_CHALLENGE);
        }
        if (requireConsumed != (challenge.consumedAt() != null)) {
            throw IdentityApplicationException.of(challenge.consumedAt() == null
                    ? Code.INVALID_CHALLENGE : Code.CHALLENGE_CONSUMED);
        }
        return challenge;
    }

    private IssuedSession issueSession(UUID accountId, int generation, AuthStrength strength) {
        Instant now = clock.instant();
        IssuedToken token = secrets.issueToken();
        SessionDescriptor descriptor = new SessionDescriptor(UUID.randomUUID(), accountId,
                token.digest(), token.digestKeyId(), strength, generation, now, now,
                now.plus(SESSION_LIFETIME), null);
        store.createSession(descriptor);
        return new IssuedSession(token.plaintext(), descriptor);
    }

    private List<String> issueRecoveryCodes(UUID accountId, int generation) {
        List<String> plaintext = new ArrayList<>();
        List<byte[]> digests = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            IssuedToken code = secrets.issueToken();
            plaintext.add(code.plaintext());
            digests.add(code.digest());
        }
        store.saveRecoveryCodes(accountId, generation, DIGEST_KEY_ID, digests,
                clock.instant().plus(RECOVERY_CODE_LIFETIME));
        return List.copyOf(plaintext);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw IdentityApplicationException.of(Code.BUSINESS_RULE_VIOLATION);
        }
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    public record TotpEnrollment(UUID enrollmentId, String token, String secret, Instant expiresAt) {}
    public record EnrollmentCompletion(List<String> recoveryCodes, IssuedSession session) {}
    public record IssuedSession(String token, SessionDescriptor descriptor) {}
}
