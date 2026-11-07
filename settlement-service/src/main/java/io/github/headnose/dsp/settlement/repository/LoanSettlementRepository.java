package io.github.headnose.dsp.settlement.repository;

import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.model.SettlementStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface LoanSettlementRepository extends MongoRepository<LoanSettlementDocument, String> {

    List<LoanSettlementDocument> findByLoanDealerDealerId(String dealerId);

    List<LoanSettlementDocument> findByStatus(SettlementStatus status);
}