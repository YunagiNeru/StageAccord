package com.stageaccord.filehandling.domain;

import java.util.HashMap;
import java.util.List;

public record MultipartUploadPlan(long totalSize, int partCount) {
    public static final long MAX_FILE_SIZE = 4_000_000_000L;
    public static final long PART_SIZE = 64L * 1024L * 1024L;
    public static final int MAX_PARTS = 60;

    public static MultipartUploadPlan forSize(long totalSize) {
        if (totalSize < 1 || totalSize > MAX_FILE_SIZE) fail(FileRuleViolation.Reason.INVALID_UPLOAD_SIZE);
        int count = Math.toIntExact((totalSize + PART_SIZE - 1) / PART_SIZE);
        if (count > MAX_PARTS) fail(FileRuleViolation.Reason.INVALID_UPLOAD_SIZE);
        return new MultipartUploadPlan(totalSize, count);
    }

    public void verifyCompletedParts(List<UploadedPart> parts) {
        if (parts == null || parts.size() != partCount) fail(FileRuleViolation.Reason.INCOMPLETE_UPLOAD);
        var byNumber = new HashMap<Integer, UploadedPart>();
        for (UploadedPart part : parts) {
            if (part == null || byNumber.put(part.number(), part) != null) {
                fail(FileRuleViolation.Reason.INVALID_UPLOAD_PART);
            }
        }
        for (int number = 1; number <= partCount; number++) {
            UploadedPart part = byNumber.get(number);
            long expected = number == partCount ? totalSize - PART_SIZE * (partCount - 1L) : PART_SIZE;
            if (part == null || part.size() != expected || part.sha256() == null || part.sha256().length != 32) {
                fail(FileRuleViolation.Reason.INVALID_UPLOAD_PART);
            }
        }
    }

    public record UploadedPart(int number, long size, byte[] sha256) {
        public UploadedPart {
            sha256 = sha256 == null ? null : sha256.clone();
        }
        @Override public byte[] sha256() { return sha256 == null ? null : sha256.clone(); }
    }

    private static void fail(FileRuleViolation.Reason reason) { throw new FileRuleViolation(reason); }
}
