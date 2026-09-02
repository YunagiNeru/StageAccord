package com.stageaccord.sharedkernel.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class IdempotencyHasherTest {
    private final IdempotencyHasher hasher = new IdempotencyHasher(
            new SecretKeySpec(new byte[32], "HmacSHA256"));

    @Test
    void separatesScopeKeyAndCanonicalRequest() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        var first = hasher.fingerprint(actor, workspace, "CreateProject", "opaque-key",
                "{\"value\":1}".getBytes(StandardCharsets.UTF_8));
        var same = hasher.fingerprint(actor, workspace, "CreateProject", "opaque-key",
                "{\"value\":1}".getBytes(StandardCharsets.UTF_8));
        var changed = hasher.fingerprint(actor, workspace, "CreateProject", "opaque-key",
                "{\"value\":2}".getBytes(StandardCharsets.UTF_8));

        assertThat(first.scopeHash()).isEqualTo(same.scopeHash());
        assertThat(first.keyHash()).isEqualTo(same.keyHash());
        assertThat(first.requestHash()).isEqualTo(same.requestHash());
        assertThat(changed.scopeHash()).isEqualTo(first.scopeHash());
        assertThat(changed.keyHash()).isEqualTo(first.keyHash());
        assertThat(changed.requestHash()).isNotEqualTo(first.requestHash());
    }
}
