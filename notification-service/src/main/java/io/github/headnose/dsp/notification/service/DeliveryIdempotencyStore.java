package io.github.headnose.dsp.notification.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * BlastMail's idempotency keys in its own Redis (B3), one key per recipientId:
 * PROCESSING (short TTL, set atomically before sending) -> SENT (long TTL, after SMTP accepted).
 * Deleted again if sending fails, so a retry or DLQ replay can send.
 */
@Component
public class DeliveryIdempotencyStore {

    public enum ClaimResult { CLAIMED, ALREADY_SENT, IN_PROGRESS }

    static final String KEY_PREFIX = "blastmail:delivery:";
    static final String PROCESSING = "PROCESSING";
    static final String SENT = "SENT";

    private final StringRedisTemplate redis;
    private final Duration processingTtl;
    private final Duration sentTtl;

    public DeliveryIdempotencyStore(StringRedisTemplate redis,
                                    @Value("${blastmail.idempotency.processing-ttl}") Duration processingTtl,
                                    @Value("${blastmail.idempotency.sent-ttl}") Duration sentTtl) {
        this.redis = redis;
        this.processingTtl = processingTtl;
        this.sentTtl = sentTtl;
    }

    /** SET key PROCESSING NX EX: only one attempt can own a recipient at a time. */
    public ClaimResult claim(String recipientId) {
        String key = key(recipientId);
        Boolean claimed = redis.opsForValue().setIfAbsent(key, PROCESSING, processingTtl);
        if (Boolean.TRUE.equals(claimed)) {
            return ClaimResult.CLAIMED;
        }
        // Key exists: SENT, or PROCESSING. If it expired between the two calls (null),
        // IN_PROGRESS is still safe: the retry will claim it.
        return SENT.equals(redis.opsForValue().get(key)) ? ClaimResult.ALREADY_SENT : ClaimResult.IN_PROGRESS;
    }

    public void markSent(String recipientId) {
        redis.opsForValue().set(key(recipientId), SENT, sentTtl);
    }

    public void release(String recipientId) {
        redis.delete(key(recipientId));
    }

    static String key(String recipientId) {
        return KEY_PREFIX + recipientId;
    }
}