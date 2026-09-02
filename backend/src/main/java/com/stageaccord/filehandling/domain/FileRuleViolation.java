package com.stageaccord.filehandling.domain;

public final class FileRuleViolation extends RuntimeException {
    private final Reason reason;

    public FileRuleViolation(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public enum Reason {
        INVALID_UPLOAD_SIZE,
        INVALID_UPLOAD_PART,
        INCOMPLETE_UPLOAD,
        SCAN_EVIDENCE_MISSING,
        SCAN_EVIDENCE_MISMATCH,
        PROMOTION_EVIDENCE_MISMATCH,
        FILE_NOT_READY,
        AUTHORIZATION_UNAVAILABLE,
        AUTHORIZATION_STALE,
        ACCESS_DENIED,
        GRANT_EXPIRED,
        GRANT_REVOKED,
        GRANT_EXHAUSTED,
        INVALID_EXTERNAL_URL
    }
}
