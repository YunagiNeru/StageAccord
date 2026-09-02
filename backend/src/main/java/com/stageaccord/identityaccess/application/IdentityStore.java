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

    void refreshSession(UUID sessionId, Instant authenticatedAt);

    List<AuthFactorDescriptor> listFactors(UUID accountId);

    int countActiveCredentials(UUID accountId, String type);

    void revokeCredentials(UUID accountId, String type, UUID credentialId);

    int replacePasswordAndAdvanceGeneration(UUID accountId, String encodedPassword);

    int advanceAuthGeneration(UUID accountId);

    void invalidateRecoveryCodes(UUID accountId, Instant usedAt);

    UUID createRecoveryCase(UUID accountId, String method, Instant requestedAt, Instant notBefore);

    Optional<RecoveryCase> lockRecoveryCase(UUID id);

    boolean consumeRecoveryCode(UUID accountId, int generation, byte[] digest, Instant usedAt);

    void completeRecoveryCase(UUID id, Instant completedAt);

    Optional<ClientAccessGrant> lockClientAccessGrant(byte[] tokenDigest, String digestKeyId);

    void consumeClientAccessGrant(UUID workspaceId, UUID id, Instant consumedAt);

    void createClientSession(ClientAccessGrant grant, SessionDescriptor session);

    void createPasskey(UUID accountId, UUID credentialId, String webAuthnCredentialId);

    Optional<String> findPasskeyExternalId(UUID accountId, UUID credentialId);

    void activateAccount(UUID accountId);

    Optional<ClientSessionDescriptor> findClientSession(byte[] tokenDigest, String digestKeyId);

    void touchClientSession(UUID workspaceId, UUID sessionId, Instant lastSeenAt);
}
