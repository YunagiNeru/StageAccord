package com.stageaccord.auditadmin.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndependentDeletionLedgerTest {
    @TempDir java.nio.file.Path directory;

    @Test
    void signedHashChainDetectsTamperingAndReappliesOnlyVerifiedEntries() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var path = directory.resolve("deletion-ledger.log");
        var ledger = new IndependentDeletionLedger(path);
        byte[] subject = new byte[32];
        ledger.append(IndependentDeletionLedger.Action.DELETE, subject, "{}".getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-09-02T10:00:00Z"), "dummy-key", keys.getPrivate(), keys.getPublic());
        ledger.append(IndependentDeletionLedger.Action.REVOKE_ACCESS, subject,
                "{\"scope\":\"all\"}".getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-09-02T10:01:00Z"), "dummy-key", keys.getPrivate(), keys.getPublic());

        var applied = new ArrayList<Long>();
        var result = ledger.reapply(keys.getPublic(), List.of(entry -> applied.add(entry.sequence())));
        assertThat(result.appliedEntries()).isEqualTo(2);
        assertThat(applied).containsExactly(1L, 2L);

        String changed = Files.readString(path).replaceFirst("DELETE", "UNPUBLISH");
        Files.writeString(path, changed);
        var broken = ledger.verify(keys.getPublic());
        assertThat(broken.valid()).isFalse();
        assertThat(broken.failedSequence()).isEqualTo(1L);
    }
}
