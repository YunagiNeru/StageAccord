package com.stageaccord.identityaccess.api;

public interface IdentityAccessGateway {
    AuthenticatedPrincipal resolve(String sessionToken);

    IssuedOpaqueToken issueToken();

    byte[] emailDigest(String email);

    byte[] tokenDigest(String token);
}
