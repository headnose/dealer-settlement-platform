package io.github.headnose.dsp.settlement.cache;

import io.github.headnose.dsp.settlement.MongoTestContainerConfig;
import io.github.headnose.dsp.settlement.model.LoanInfo;
import io.github.headnose.dsp.settlement.model.SettlementInfo;
import io.github.headnose.dsp.settlement.model.SettlementStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static io.github.headnose.dsp.settlement.SettlementTestData.LOAN_ID;
import static io.github.headnose.dsp.settlement.SettlementTestData.closedLoan;
import static io.github.headnose.dsp.settlement.SettlementTestData.money;
import static io.github.headnose.dsp.settlement.SettlementTestData.wirePayment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * C2: Redis down never fails a request. Nothing listens on port 1, so every Redis call gets a real
 * "connection refused" from Lettuce. This proves Spring translates it into a DataAccessException
 * that the cache catches (a mock would only prove what we told it to throw). Own context on purpose.
 */
@SpringBootTest(properties = "spring.data.redis.port=1")
@Import(MongoTestContainerConfig.class)
class SettlementDetailCacheRedisDownTest {

    @Autowired
    private SettlementDetailCache cache;

    @Test
    void everyOperationFailsOpen() {
        LoanInfo loan = closedLoan();
        CachedSettlementDetail detail = new CachedSettlementDetail(loan, SettlementStatus.CALCULATED,
                new SettlementInfo("SET-1", money("28500.00"), money("0.00"), money("28500.00")),
                wirePayment(), List.of());

        assertThat(cache.get(LOAN_ID)).isEmpty();
        assertThatCode(() -> cache.put(detail)).doesNotThrowAnyException();
        assertThatCode(() -> cache.evict(LOAN_ID)).doesNotThrowAnyException();
    }
}