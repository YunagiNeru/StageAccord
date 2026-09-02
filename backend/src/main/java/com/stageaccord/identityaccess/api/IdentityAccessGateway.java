package com.stageaccord.identityaccess.api;

import java.util.UUID;

public interface IdentityAccessGateway {
    AuthenticatedPrincipal resolve(String sessionToken);

    AuthenticatedClient resolveClient(String sessionToken);

    IssuedOpaqueToken issueToken();

    byte[] emailDigest(String email);

    byte[] tokenDigest(String token);

    ProtectedContact protectContact(String value);

    String revealContact(ProtectedContact value);

    void issueClientLink(UUID workspaceId, UUID projectId, String email, String role);
}
