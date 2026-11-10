package io.github.headnose.dsp.settlement.document;

import io.github.headnose.dsp.settlement.model.AdjustmentType;
import io.github.headnose.dsp.settlement.model.PaymentMethod;
import io.github.headnose.dsp.settlement.model.SettlementStatus;
import io.github.headnose.dsp.settlement.repository.LoanSettlementRepository;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
// ⬇️ copy the @DataMongoTest import line from your existing Mongo test
// import org.springframework.boot. ... .DataMongoTest;

import java.util.List;

import static io.github.headnose.dsp.settlement.SettlementTestData.*;
import static org.assertj.core.api.Assertions.assertThat;

@DataMongoTest
// ⬆️ plus any other class annotations your existing Mongo test has
class LoanSettlementDocumentMongoTest {

    @Autowired
    private LoanSettlementRepository repository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanCollection() {
        repository.deleteAll();
    }

    @Test
    void calculatedSettlementRoundTripsWithExactValues() {
        saveCalculatedSettlement();

        LoanSettlementDocument reloaded = repository.findById(LOAN_ID).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(SettlementStatus.CALCULATED);
        assertThat(reloaded.getPayment().method()).isEqualTo(PaymentMethod.WIRE);
        assertThat(reloaded.getPayment().beneficiaryBank()).isEqualTo("Sunrise Auto Group Bank");
        assertThat(reloaded.getSettlement())
                .isEqualTo(settlement("28500.00", "650.00", "27850.00"));
        assertThat(reloaded.getAdjustments()).containsExactly(
                adj(AdjustmentType.DEALER_HOLDBACK, "500.00"),
                adj(AdjustmentType.DOC_FEE, "150.00"));
        assertThat(reloaded.getVersion()).isEqualTo(1L);   // 0 on insert, 1 after the update
    }

    @Test
    void moneyIsStoredAsDecimal128() {
        saveCalculatedSettlement();

        Document raw = mongoTemplate.getCollection("loan_settlements")
                .find(new Document("_id", LOAN_ID))
                .first();

        Document settlement = raw.get("settlement", Document.class);
        assertThat(settlement.get("grossAmount")).isInstanceOf(Decimal128.class);
        assertThat(settlement.get("totalAdjustment")).isInstanceOf(Decimal128.class);
        assertThat(settlement.get("netSettlement")).isInstanceOf(Decimal128.class);

        List<Document> adjustments = raw.getList("adjustments", Document.class);
        assertThat(adjustments.get(0).get("amount")).isInstanceOf(Decimal128.class);
    }

    /** Import, then calculate, the same way the real service will: two separate saves. */
    private void saveCalculatedSettlement() {
        repository.save(LoanSettlementDocument.imported(closedLoan()));

        LoanSettlementDocument doc = repository.findById(LOAN_ID).orElseThrow();
        doc.applySettlement(wirePayment(),
                List.of(adj(AdjustmentType.DEALER_HOLDBACK, "500.00"),
                        adj(AdjustmentType.DOC_FEE, "150.00")),
                settlement("28500.00", "650.00", "27850.00"));
        repository.save(doc);
    }
}
