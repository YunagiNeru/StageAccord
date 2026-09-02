package com.stageaccord.identityaccess;

import org.springframework.stereotype.Component;

import com.stageaccord.identityaccess.api.AuthenticatedPrincipal;
import com.stageaccord.identityaccess.api.AuthenticatedClient;
import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.identityaccess.api.IssuedOpaqueToken;
import com.stageaccord.identityaccess.api.ProtectedContact;
import com.stageaccord.identityaccess.application.IdentityAccessService;
import com.stageaccord.identityaccess.application.IdentitySecretStore;
import com.stageaccord.identityaccess.application.IdentityStore;

@Component
public final class DefaultIdentityAccessGateway implements IdentityAccessGateway {
    private final IdentityAccessService service;
    private final IdentityStore store;
    private final IdentitySecretStore secrets;

    public DefaultIdentityAccessGateway(IdentityAccessService service, IdentityStore store,
            IdentitySecretStore secrets) {
        this.service = service;
        this.store = store;
        this.secrets = secrets;
    }

    @Override
    public AuthenticatedPrincipal resolve(String sessionToken) {
        var session = service.resolveSession(sessionToken);
        var account = store.findAuthenticationByAccountId(session.accountId()).orElseThrow();
        return new AuthenticatedPrincipal(session.accountId(), account.emailDigest(),
                session.strength().name().toLowerCase(java.util.Locale.ROOT), session.authenticatedAt());
    }

    @Override
    public AuthenticatedClient resolveClient(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw com.stageaccord.identityaccess.application.IdentityApplicationException.of(
                    com.stageaccord.identityaccess.application.IdentityApplicationException.Code.AUTHENTICATION_REQUIRED);
        }
        var session = store.findClientSession(secrets.tokenDigest(sessionToken), "identity-v1")
                .filter(item -> item.revokedAt() == null && java.time.Instant.now().isBefore(item.absoluteExpiresAt()))
                .orElseThrow(() -> com.stageaccord.identityaccess.application.IdentityApplicationException.of(
                        com.stageaccord.identityaccess.application.IdentityApplicationException.Code.AUTHENTICATION_REQUIRED));
        store.touchClientSession(session.workspaceId(), session.id(), java.time.Instant.now());
        return new AuthenticatedClient(session.workspaceId(), session.id(), session.projectId(), session.role(),
                session.authGeneration(), session.authenticatedAt(), session.absoluteExpiresAt());
    }

    @Override
    public IssuedOpaqueToken issueToken() {
        var token = secrets.issueToken();
        return new IssuedOpaqueToken(token.plaintext(), token.digest(), token.digestKeyId());
    }

    @Override public byte[] emailDigest(String email) { return secrets.emailDigest(email); }
    @Override public byte[] tokenDigest(String token) { return secrets.tokenDigest(token); }
    @Override public ProtectedContact protectContact(String value) {
        var protectedValue = secrets.protect(value);
        return new ProtectedContact(protectedValue.keyId(), protectedValue.algorithm(),
                protectedValue.nonce(), protectedValue.ciphertext());
    }
    @Override public String revealContact(ProtectedContact value) {
        return secrets.reveal(new com.stageaccord.identityaccess.application.ProtectedValue(
                value.keyId(), value.algorithm(), value.nonce(), value.ciphertext()));
    }
    @Override public void issueClientLink(java.util.UUID workspaceId, java.util.UUID projectId,
            String email, String role) {
        service.issueClientLink(workspaceId, projectId, email, role);
    }
}
