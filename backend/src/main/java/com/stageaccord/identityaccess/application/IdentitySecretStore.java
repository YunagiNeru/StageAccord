package com.stageaccord.identityaccess.application;

public interface IdentitySecretStore {
    byte[] emailDigest(String email);

    byte[] tokenDigest(String token);

    ProtectedValue protect(String value);

    String reveal(ProtectedValue value);

    String hashPassword(String password);

    boolean passwordMatches(String password, String encodedPassword);

    IssuedToken issueToken();
}
