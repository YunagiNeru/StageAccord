package com.stageaccord.identityaccess.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityStore {
    void createChallenge(AuthChallenge challenge);

    Optional<AuthChallenge> lockChallenge(UUID id);

    void consumeChallenge(UUID id, Instant consumedAt);

    void attachChallengeToAccount(UUID id, UUID accountId);

    void createAccount(UUID accountId, byte[] emailDigest, ProtectedValue protectedEmail,
            String encodedPassword, Instant now);

    void createPendingTotp(UUID accountId, UUID credentialId, ProtectedValue protectedSecret);

    ProtectedValue requirePendingTotp(UUID accountId, UUID credentialId);

    void activateTotpAndAccount(UUID accountId, UUID credentialId);

    void saveRecoveryCodes(UUID accountId, int generation, String digestKeyId,
            List<byte[]> digests, Instant expiresAt);

    Optional<AccountAuthentication> findAuthentication(byte[] emailDigest);

    Optional<AccountAuthentication> findAuthenticationByAccountId(UUID accountId);

    void createSession(SessionDescriptor session);

    Optional<SessionDescriptor> findSession(byte[] tokenDigest, String digestKeyId);

    void touchSession(UUID sessionId, Instant lastSeenAt);

    List<SessionDescriptor> listSessions(UUID accountId);

    void revokeSession(UUID accountId, UUID sessionId, Instant revokedAt);

    void revokeAllSessions(UUID accountId, Instant revokedAt);
}
