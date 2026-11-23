package io.github.headnose.dsp.settlement.cache;

import io.github.headnose.dsp.settlement.MongoTestContainerConfig;
import io.github.headnose.dsp.settlement.RedisTestContainerConfig;
import io.github.headnose.dsp.settlement.model.AdjustmentType;
import io.github.headnose.dsp.settlement.model.LoanInfo;
import io.github.headnose.dsp.settlement.model.SettlementInfo;
import io.github.headnose.dsp.settlement.model.SettlementStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.github.headnose.dsp.settlement.SettlementTestData.adj;
import static io.github.headnose.dsp.settlement.SettlementTestData.closedLoan;
import static io.github.headnose.dsp.settlement.SettlementTestData.money;
import static io.github.headnose.dsp.settlement.SettlementTestData.wirePayment;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({MongoTestContainerConfig.class, RedisTestContainerConfig.class})
class SettlementDetailCacheIntegrationTest {

    @Autowired
    private SettlementDetailCache cache;

    @Autowired
    private StringRedisTemplate redis;

    /** Unique per test: the Redis container is shared by every test in this context. */
    private static String uniqueLoanId() {
        return "LN-CACHE-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static CachedSettlementDetail calculatedDetail(String loanId) {
        LoanInfo base = closedLoan();
        LoanInfo loan = new LoanInfo(loanId, base.dealer(), base.status(), base.currency(),
                base.principalAmount(), base.closingDate());
        return new CachedSettlementDetail(
                loan,
                SettlementStatus.CALCULATED,
                new SettlementInfo("SET-" + loanId, money("28500.00"), money("650.00"), money("27850.00")),
                wirePayment(),
                List.of(adj(AdjustmentType.DEALER_HOLDBACK, "500.00"), adj(AdjustmentType.DOC_FEE, "150.00")));
    }

    @Test
    void roundTripKeepsEveryField() {
        String loanId = uniqueLoanId();
        CachedSettlementDetail detail = calculatedDetail(loanId);

        cache.put(detail);

        // Record equality covers every field, incl. BigDecimal scale (28500.00 != 28500.0)
        assertThat(cache.get(loanId)).contains(detail);
        // The fields an API-DTO round trip would have lost (PaymentDto READ_ONLY, see C6)
        CachedSettlementDetail read = cache.get(loanId).orElseThrow();
        assertThat(read.payment().beneficiaryBank()).isEqualTo("Sunrise Auto Group Bank");
        assertThat(read.payment().valueDate()).isEqualTo(wirePayment().valueDate());
    }

    @Test
    void putUsesKeyPrefixAndConfiguredTtl() {
        String loanId = uniqueLoanId();

        cache.put(calculatedDetail(loanId));

        // A misspelled settlement-cache.ttl would fail at startup, but a wrong value would not: check it
        Long ttlSeconds = redis.getExpire("lsc:loan:" + loanId, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isBetween(1790L, 1800L);
    }

    @Test
    void missIsEmpty() {
        assertThat(cache.get(uniqueLoanId())).isEmpty();
    }

    @Test
    void evictRemovesEntry() {
        String loanId = uniqueLoanId();
        cache.put(calculatedDetail(loanId));

        cache.evict(loanId);

        assertThat(cache.get(loanId)).isEmpty();
    }

    @Test
    void unreadableEntryIsAMissAndIsEvicted() {
        String loanId = uniqueLoanId();
        String key = SettlementDetailCache.key(loanId);
        redis.opsForValue().set(key, "not json");

        assertThat(cache.get(loanId)).isEmpty();
        assertThat(redis.hasKey(key)).isFalse();
    }
}