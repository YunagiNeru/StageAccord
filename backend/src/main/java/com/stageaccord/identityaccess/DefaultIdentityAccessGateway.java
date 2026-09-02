package com.stageaccord.identityaccess;

import org.springframework.stereotype.Component;

import com.stageaccord.identityaccess.api.AuthenticatedPrincipal;
import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.identityaccess.api.IssuedOpaqueToken;
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
    public IssuedOpaqueToken issueToken() {
        var token = secrets.issueToken();
        return new IssuedOpaqueToken(token.plaintext(), token.digest(), token.digestKeyId());
    }

    @Override public byte[] emailDigest(String email) { return secrets.emailDigest(email); }
    @Override public byte[] tokenDigest(String token) { return secrets.tokenDigest(token); }
}
