package com.stageaccord.identityaccess.application;

public record ProtectedValue(String keyId, String algorithm, String nonce, String ciphertext) {}
