package com.stageaccord.agreement.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.erdtman.jcs.JsonCanonicalizer;

public final class AgreementCanonicalizer {
    public CanonicalAgreement canonicalize(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("agreement json must not be blank");
        try {
            byte[] canonical = new JsonCanonicalizer(json).getEncodedUTF8();
            return new CanonicalAgreement(canonical, MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("agreement json is not valid I-JSON", error);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public boolean matches(String json, byte[] expectedHash) {
        return expectedHash != null && MessageDigest.isEqual(canonicalize(json).sha256(), expectedHash);
    }
}
