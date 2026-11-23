package io.github.headnose.dsp.settlement.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache-aside store for GET /api/v1/loans/{loanId}/settlements (C1-C3, C6), in LSC's own Redis.
 * Key lsc:loan:{loanId}, value = CachedSettlementDetail as JSON, TTL settlement-cache.ttl.
 *
 * C2: the cache is an optimization, never a dependency. Every Redis failure is logged at WARN and
 * swallowed: get() reports a miss (caller reads MongoDB), put() and evict() do nothing.
 */
@Component
public class SettlementDetailCache {

    static final String KEY_PREFIX = "lsc:loan:";

    private static final Logger log = LoggerFactory.getLogger(SettlementDetailCache.class);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final Duration ttl;

    public SettlementDetailCache(StringRedisTemplate redis,
                                 JsonMapper jsonMapper,
                                 @Value("${settlement-cache.ttl}") Duration ttl) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.ttl = ttl;
    }

    /** Empty = miss, Redis down, or an unreadable entry. The caller then reads MongoDB. */
    public Optional<CachedSettlementDetail> get(String loanId) {
        String json;
        try {
            json = redis.opsForValue().get(key(loanId));
        } catch (DataAccessException e) {
            log.warn("Settlement cache read failed for loan {}, reading MongoDB: {}", loanId, e.getMessage());
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(jsonMapper.readValue(json, CachedSettlementDetail.class));
        } catch (JacksonException e) {
            // e.g. an entry written by an older version of this class: drop it, MongoDB refills it
            log.warn("Unreadable settlement cache entry for loan {}, evicting: {}", loanId, e.getMessage());
            evict(loanId);
            return Optional.empty();
        }
    }

    /** SET key value EX ttl. A failure only means the next read is a miss. */
    public void put(CachedSettlementDetail detail) {
        String loanId = detail.loan().loanId();
        try {
            redis.opsForValue().set(key(loanId), jsonMapper.writeValueAsString(detail), ttl);
        } catch (DataAccessException | JacksonException e) {
            log.warn("Settlement cache write failed for loan {}: {}", loanId, e.getMessage());
        }
    }

    /**
     * DEL key, called after every write to the loan's document. A failure is the one case that
     * matters: the old entry may survive until its TTL, so the WARN names the key to delete by hand.
     */
    public void evict(String loanId) {
        try {
            redis.delete(key(loanId));
        } catch (DataAccessException e) {
            log.warn("Settlement cache evict failed, stale entry possible until TTL: key {}: {}",
                    key(loanId), e.getMessage());
        }
    }

    static String key(String loanId) {
        return KEY_PREFIX + loanId;
    }
}