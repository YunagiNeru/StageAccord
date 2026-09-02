package com.stageaccord.intake.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.stageaccord.sharedkernel.web.ApiFailure;

class ValkeyIntakeRateGateTest {
    @SuppressWarnings("unchecked")
    @Test
    void allowsFiveRequestsAndFailsClosedWhenValkeyIsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(5L, 6L)
                .thenThrow(new RedisConnectionFailureException("down"));
        var gate = new ValkeyIntakeRateGate(redis);

        assertThat(gate.allow(UUID.randomUUID(), new byte[32])).isTrue();
        assertThat(gate.allow(UUID.randomUUID(), new byte[32])).isFalse();
        assertThatThrownBy(() -> gate.allow(UUID.randomUUID(), new byte[32]))
                .isInstanceOf(ApiFailure.class)
                .hasMessage("RATE_SERVICE_UNAVAILABLE");
    }
}
