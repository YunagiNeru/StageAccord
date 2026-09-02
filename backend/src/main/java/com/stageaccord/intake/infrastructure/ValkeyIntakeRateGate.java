package com.stageaccord.intake.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.stageaccord.intake.application.IntakeRateGate;
import com.stageaccord.sharedkernel.web.ApiFailure;

import org.springframework.http.HttpStatus;

@Component
final class ValkeyIntakeRateGate implements IntakeRateGate {
    private static final long LIMIT = 5;
    private final StringRedisTemplate redis;

    ValkeyIntakeRateGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean allow(UUID workspaceId, byte[] subjectDigest) {
        Instant window = Instant.now().truncatedTo(ChronoUnit.HOURS);
        String key = "intake:" + workspaceId + ":" + window + ":" + HexFormat.of().formatHex(subjectDigest);
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) redis.expire(key, Duration.ofHours(2));
            return count != null && count <= LIMIT;
        } catch (DataAccessException failure) {
            throw ApiFailure.of(HttpStatus.SERVICE_UNAVAILABLE, "RATE_SERVICE_UNAVAILABLE");
        }
    }
}
