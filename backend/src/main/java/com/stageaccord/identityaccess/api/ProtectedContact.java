package com.stageaccord.identityaccess.api;

public record ProtectedContact(String keyId, String algorithm, String nonce, String ciphertext) {}
