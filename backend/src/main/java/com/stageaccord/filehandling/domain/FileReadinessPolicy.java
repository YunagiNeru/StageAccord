package com.stageaccord.filehandling.domain;

import java.util.Arrays;

public final class FileReadinessPolicy {
    public void requireReady(FileVersion file, ScanEvidence scan, PromotionReceipt receipt) {
        if (file == null || receipt == null) fail(FileRuleViolation.Reason.PROMOTION_EVIDENCE_MISMATCH);
        if (file.scanMode() == ScanMode.REQUIRED) requireCompleteNegativeScan(file, scan);
        else requireRecordedBypass(scan);
        if (receipt.sizeBytes() != file.sizeBytes()
                || !Arrays.equals(receipt.sha256(), file.sha256())
                || !receipt.destinationBucket().equals(file.destinationBucket())
                || !receipt.destinationVersionId().equals(file.destinationVersionId())) {
            fail(FileRuleViolation.Reason.PROMOTION_EVIDENCE_MISMATCH);
        }
    }

    private static void requireCompleteNegativeScan(FileVersion file, ScanEvidence scan) {
        if (scan == null) fail(FileRuleViolation.Reason.SCAN_EVIDENCE_MISSING);
        if (scan.mode() != ScanMode.REQUIRED || scan.result() != ScanResult.NEGATIVE
                || scan.engine() == null || scan.definitionVersion() == null
                || scan.bytesRead() != file.sizeBytes() || scan.bytesScanned() != file.sizeBytes()) {
            fail(FileRuleViolation.Reason.SCAN_EVIDENCE_MISMATCH);
        }
    }

    private static void requireRecordedBypass(ScanEvidence scan) {
        if (scan == null) fail(FileRuleViolation.Reason.SCAN_EVIDENCE_MISSING);
        if (scan.mode() != ScanMode.BYPASS || scan.result() != ScanResult.BYPASSED
                || scan.engine() != null || scan.definitionVersion() != null) {
            fail(FileRuleViolation.Reason.SCAN_EVIDENCE_MISMATCH);
        }
    }

    private static void fail(FileRuleViolation.Reason reason) { throw new FileRuleViolation(reason); }

    public record FileVersion(long sizeBytes, byte[] sha256, ScanMode scanMode,
            String destinationBucket, String destinationVersionId) {
        public FileVersion { sha256 = sha256 == null ? null : sha256.clone(); }
        @Override public byte[] sha256() { return sha256 == null ? null : sha256.clone(); }
    }
    public record ScanEvidence(ScanMode mode, String engine, String definitionVersion,
            long bytesRead, long bytesScanned, ScanResult result) {}
    public record PromotionReceipt(long sizeBytes, byte[] sha256,
            String destinationBucket, String destinationVersionId) {
        public PromotionReceipt { sha256 = sha256 == null ? null : sha256.clone(); }
        @Override public byte[] sha256() { return sha256 == null ? null : sha256.clone(); }
    }
    public enum ScanMode { REQUIRED, BYPASS }
    public enum ScanResult { NEGATIVE, POSITIVE, FAILED, BYPASSED }
}
