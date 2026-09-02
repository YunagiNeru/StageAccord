package com.stageaccord.filehandling.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.stageaccord.filehandling.application.ExternalLinkValidator;
import com.stageaccord.filehandling.domain.FileReadinessPolicy.FileVersion;
import com.stageaccord.filehandling.domain.FileReadinessPolicy.PromotionReceipt;
import com.stageaccord.filehandling.domain.FileReadinessPolicy.ScanEvidence;
import com.stageaccord.filehandling.domain.FileReadinessPolicy.ScanMode;
import com.stageaccord.filehandling.domain.FileReadinessPolicy.ScanResult;

class FileSafetyPolicyTest {
    private static final byte[] HASH = new byte[32];
    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");

    @Test
    void fourGigabyteUploadUsesSixtyExactParts() {
        MultipartUploadPlan plan = MultipartUploadPlan.forSize(4_000_000_000L);
        assertThat(plan.partCount()).isEqualTo(60);
        var parts = java.util.stream.IntStream.rangeClosed(1, 60)
                .mapToObj(number -> new MultipartUploadPlan.UploadedPart(number,
                        number == 60 ? 4_000_000_000L - 59 * MultipartUploadPlan.PART_SIZE
                                : MultipartUploadPlan.PART_SIZE, HASH))
                .toList();
        plan.verifyCompletedParts(parts);
        assertThatThrownBy(() -> MultipartUploadPlan.forSize(4_000_000_001L))
                .isInstanceOf(FileRuleViolation.class);
    }

    @Test
    void uploadRejectsMissingDuplicateAndWrongSizedParts() {
        MultipartUploadPlan plan = MultipartUploadPlan.forSize(MultipartUploadPlan.PART_SIZE + 1);
        assertThatThrownBy(() -> plan.verifyCompletedParts(List.of(
                new MultipartUploadPlan.UploadedPart(1, MultipartUploadPlan.PART_SIZE, HASH))))
                .isInstanceOf(FileRuleViolation.class);
        assertThatThrownBy(() -> plan.verifyCompletedParts(List.of(
                new MultipartUploadPlan.UploadedPart(1, MultipartUploadPlan.PART_SIZE, HASH),
                new MultipartUploadPlan.UploadedPart(1, 1, HASH))))
                .isInstanceOf(FileRuleViolation.class);
    }

    @Test
    void requiredScanMustReadEveryByteAndMatchPromotionReceipt() {
        var policy = new FileReadinessPolicy();
        var file = new FileVersion(128, HASH, ScanMode.REQUIRED, "ready", "version-1");
        var receipt = new PromotionReceipt(128, HASH, "ready", "version-1");
        assertThatThrownBy(() -> policy.requireReady(file,
                new ScanEvidence(ScanMode.REQUIRED, "scanner", "defs", 128, 127, ScanResult.NEGATIVE), receipt))
                .isInstanceOf(FileRuleViolation.class);
        policy.requireReady(file,
                new ScanEvidence(ScanMode.REQUIRED, "scanner", "defs", 128, 128, ScanResult.NEGATIVE), receipt);
        assertThatThrownBy(() -> policy.requireReady(file,
                new ScanEvidence(ScanMode.REQUIRED, "scanner", "defs", 128, 128, ScanResult.NEGATIVE),
                new PromotionReceipt(127, HASH, "ready", "version-1")))
                .isInstanceOf(FileRuleViolation.class);
    }

    @Test
    void bypassIsExplicitEvidenceAndDoesNotPretendToRunScanner() {
        var policy = new FileReadinessPolicy();
        var file = new FileVersion(8, HASH, ScanMode.BYPASS, "ready", "version-2");
        var receipt = new PromotionReceipt(8, HASH, "ready", "version-2");
        policy.requireReady(file,
                new ScanEvidence(ScanMode.BYPASS, null, null, 0, 0, ScanResult.BYPASSED), receipt);
        assertThatThrownBy(() -> policy.requireReady(file,
                new ScanEvidence(ScanMode.BYPASS, "scanner", "defs", 8, 8, ScanResult.NEGATIVE), receipt))
                .isInstanceOf(FileRuleViolation.class);
    }

    @Test
    void grantFailsClosedWhenAuthorizationIsUnavailableStaleOrExpired() {
        var policy = new DownloadGrantPolicy();
        var grant = new DownloadGrantPolicy.Grant(true, 4, NOW.plusSeconds(300), null, 1);
        assertThatThrownBy(() -> policy.requireUsable(grant, NOW,
                new DownloadGrantPolicy.Authorization(false, 4, true)))
                .isInstanceOf(FileRuleViolation.class);
        assertThatThrownBy(() -> policy.requireUsable(grant, NOW,
                new DownloadGrantPolicy.Authorization(true, 5, true)))
                .isInstanceOf(FileRuleViolation.class);
        assertThatThrownBy(() -> policy.requireUsable(grant, NOW.plusSeconds(300),
                new DownloadGrantPolicy.Authorization(true, 4, true)))
                .isInstanceOf(FileRuleViolation.class);
        policy.requireUsable(grant, NOW, new DownloadGrantPolicy.Authorization(true, 4, true));
    }

    @Test
    void externalUrlValidationIsSyntaxOnlyAndRejectsIdentityAndLiteralHosts() {
        var policy = new ExternalLinkValidator(new ExternalHostPolicy());
        assertThat(policy.validateWithoutNetworkAccess("https://docs.example.org/a/../b").asciiHost())
                .isEqualTo("docs.example.org");
        for (String value : List.of("file:///etc/passwd", "https://user@example.org/",
                "http://127.0.0.1/", "http://[::1]/", "https://service.localhost/",
                "https://service.invalid/", "https://%31%32%37.0.0.1/")) {
            assertThatThrownBy(() -> policy.validateWithoutNetworkAccess(value))
                    .isInstanceOf(FileRuleViolation.class);
        }
    }
}
