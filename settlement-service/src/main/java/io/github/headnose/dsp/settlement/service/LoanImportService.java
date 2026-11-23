package io.github.headnose.dsp.settlement.service;

import io.github.headnose.dsp.settlement.client.LoanServiceClient;
import io.github.headnose.dsp.settlement.client.LoanServiceUnavailableException;
import io.github.headnose.dsp.settlement.client.dto.LoanDto;
import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.model.LoanStatus;
import io.github.headnose.dsp.settlement.repository.LoanSettlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@Service
public class LoanImportService {

    static final String UNAVAILABLE_MESSAGE = "Loan service temporarily unavailable";
    static final String FAILED_MESSAGE = "Import failed unexpectedly";

    private static final Logger log = LoggerFactory.getLogger(LoanImportService.class);

    private final LoanServiceClient loanServiceClient;
    private final LoanSettlementRepository repository;
    private final Executor importExecutor;

    public LoanImportService(LoanServiceClient loanServiceClient,
                             LoanSettlementRepository repository,
                             @Qualifier("loanImportExecutor") Executor importExecutor) {
        this.loanServiceClient = loanServiceClient;
        this.repository = repository;
        this.importExecutor = importExecutor;
    }

    /** Imports all loans concurrently. Returns one result per loan, in the same order as the input. */
    public List<ImportResult> importLoans(List<String> loanIds) {
        // Step A + B: start one async task per loan; turn unexpected errors into FAILED
        List<CompletableFuture<ImportResult>> futures = loanIds.stream()
                .distinct()
                .map(id -> CompletableFuture
                        .supplyAsync(() -> importOne(id), importExecutor)
                        .exceptionally(ex -> unexpectedFailure(id, ex)))
                .toList();

        // Step C: wait for every task and collect the results (same order as the input)
        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    /** Imports a single loan. Runs on a loan-import thread. Never throws for business cases. */
    ImportResult importOne(String loanId) {
        // Rule 1: already imported? (cheap local check, avoids a network call)
        if (repository.existsById(loanId)) {
            return new ImportResult(loanId, ImportOutcome.ALREADY_IMPORTED,
                    "Settlement document already exists");
        }

        // Rule 2: does loan-service know this loan? (unavailable is NOT the same as not found)
        Optional<LoanDto> response;
        try {
            response = loanServiceClient.getLoan(loanId);
        } catch (LoanServiceUnavailableException e) {
            return new ImportResult(loanId, ImportOutcome.LOAN_SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
        if (response.isEmpty()) {
            return new ImportResult(loanId, ImportOutcome.NOT_FOUND,
                    "Loan not found in loan-service");
        }
        LoanDto loan = response.get();

        // Rule 3: only CLOSED loans can be settled
        if (loan.status() != LoanStatus.CLOSED) {
            return new ImportResult(loanId, ImportOutcome.NOT_CLOSED,
                    "Loan status is " + loan.status() + ", expected CLOSED");
        }

        // Rule 4: save a new settlement document
        repository.save(LoanSettlementDocument.imported(loan.toLoanInfo()));
        return new ImportResult(loanId, ImportOutcome.IMPORTED, "Imported");
    }

    /** Anything importOne did not expect: log the real cause, return a fixed message (no internals). */
    private ImportResult unexpectedFailure(String loanId, Throwable ex) {
        // supplyAsync wraps the real exception in a CompletionException
        Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
        log.error("Import of loan {} failed unexpectedly", loanId, cause);
        return new ImportResult(loanId, ImportOutcome.FAILED, FAILED_MESSAGE);
    }
}