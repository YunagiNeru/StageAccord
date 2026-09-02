package com.stageaccord.agreement.application;

public record CanonicalAgreement(byte[] json, byte[] sha256) {
    public CanonicalAgreement {
        json = json.clone();
        sha256 = sha256.clone();
        if (sha256.length != 32) throw new IllegalArgumentException("sha256 must be 32 bytes");
    }

    @Override public byte[] json() { return json.clone(); }
    @Override public byte[] sha256() { return sha256.clone(); }
}
