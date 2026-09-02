package com.stageaccord.agreement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AgreementCanonicalizerTest {
    private final AgreementCanonicalizer canonicalizer = new AgreementCanonicalizer();

    @Test
    void canonicalizesRfc8785PropertyOrderAndNumbersBeforeHashing() {
        var result = canonicalizer.canonicalize("{\"1\":{\"f\":{\"f\":\"hi\",\"F\":5},\"\\n\":56.0},\"10\":{},\"\":\"empty\",\"a\":{},\"111\":[{\"e\":\"yes\",\"E\":\"no\"}],\"A\":{}}");
        assertThat(new String(result.json(), StandardCharsets.UTF_8))
                .isEqualTo("{\"\":\"empty\",\"1\":{\"\\n\":56,\"f\":{\"F\":5,\"f\":\"hi\"}},\"10\":{},\"111\":[{\"E\":\"no\",\"e\":\"yes\"}],\"A\":{},\"a\":{}}");
        assertThat(canonicalizer.matches(new String(result.json(), StandardCharsets.UTF_8), result.sha256())).isTrue();
    }

    @Test
    void rejectsInvalidJsonInsteadOfHashingAnApproximation() {
        assertThatThrownBy(() -> canonicalizer.canonicalize("{invalid}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
