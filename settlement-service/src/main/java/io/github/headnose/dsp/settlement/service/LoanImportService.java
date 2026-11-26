package io.github.headnose.dsp.settlement.service;

import io.github.headnose.dsp.settlement.cache.SettlementDetailCache;
import io.github.headnose.dsp.settlement.client.LoanServiceClient;
import io.github.headnose.dsp.settlement.client.LoanServiceUnavailableException;
import io.github.headnose.dsp.settlement.client.dto.LoanDto;
import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.logging.MdcKeys;
import io.github.headnose.dsp.settlement.model.LoanStatus;
import io.github.headnose.dsp.settlement.repository.LoanSettlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class LoanImportService {

    static final String UNAVAILABLE_MESSAGE = "Loan service temporarily unavailable";
    static final String FAILED_MESSAGE = "Import failed unexpectedly";
    static final String CAPACITY_MESSAGE = "Import not started: too many imports in progress, try again later";

    private static final Logger log = LoggerFactory.getLogger(LoanImportService.class);

    private final LoanServiceClient loanServiceClient;
    private final LoanSettlementRepository repository;
    private final SettlementDetailCache cache;
    private final Executor importExecutor;

    public LoanImportService(LoanServiceClient loanServiceClient,
                             LoanSettlementRepository repository,
                             SettlementDetailCache cache,
                             @Qualifier("loanImportExecutor") Executor importExecutor) {
        this.loanServiceClient = loanServiceClient;
        this.repository = repository;
        this.cache = cache;
        this.importExecutor = importExecutor;
    }

    /**
     * Imports all loans concurrently. Returns one result per loan, in the same order as the input.
     * <p>
     * If the import pool is full, the loan that was rejected and every loan after it get
     * IMPORT_CAPACITY_EXCEEDED. Loans that were already submitted are NOT cancelled: cancel() on a
     * CompletableFuture does not stop a task that is already running (it would still save the loan
     * while the user is told "not started"). Instead we wait for every submitted task, so each one
     * reports its real outcome and nothing keeps running after the response (no orphaned imports).
     */
    public List<ImportResult> importLoans(List<String> loanIds) {
        List<String> ids = loanIds.stream().distinct().toList();
        List<CompletableFuture<ImportResult>> futures = new ArrayList<>(ids.size());

        // Step A + B: start one async task per loan; turn unexpected errors into FAILED
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            try {
                futures.add(CompletableFuture
                        .supplyAsync(() -> importOne(id), importExecutor)
                        .exceptionally(ex -> unexpectedFailure(id, ex)));
            } catch (RejectedExecutionException e) {
                // Pool full (Spring's TaskRejectedException extends RejectedExecutionException).
                // Thrown on THIS thread by supplyAsync, so nothing was started for this loan or the rest.
                List<String> notStarted = ids.subList(i, ids.size());
                log.warn("Import pool full: {} of {} loans not started", notStarted.size(), ids.size());
                notStarted.forEach(rejectedId -> futures.add(CompletableFuture.completedFuture(
                        new ImportResult(rejectedId, ImportOutcome.IMPORT_CAPACITY_EXCEEDED, CAPACITY_MESSAGE))));
                break;
            }
        }

        // Step C: wait for every task and collect the results (same order as the input)
        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    /**
     * Imports a single loan. Runs on a loan-import thread. Never throws for business cases.
     * O3: one request imports several loans, so loanId is set per task, not per request: every log line of
     * this loan's import (incl. the loan-service client and its fallback) carries it. Removed when done.
     */
    ImportResult importOne(String loanId) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable(MdcKeys.LOAN_ID, loanId)) {
            return importLoan(loanId);
        }
    }

    private ImportResult importLoan(String loanId) {
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
        // C6, defensive: a new document has no cache entry, unless an old document for this loan was
        // deleted directly in MongoDB (e.g. local test data reset). Never serve that old settlement.
        cache.evict(loanId);
        return new ImportResult(loanId, ImportOutcome.IMPORTED, "Imported");
    }

    /** Anything importOne did not expect: log the real cause, return a fixed message (no internals). */
    private ImportResult unexpectedFailure(String loanId, Throwable ex) {
        // supplyAsync wraps the real exception in a CompletionException
        Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
        // O3: importOne has already removed its loanId by now
        try (MDC.MDCCloseable ignored = MDC.putCloseable(MdcKeys.LOAN_ID, loanId)) {
            log.error("Import of loan {} failed unexpectedly", loanId, cause);
        }
        return new ImportResult(loanId, ImportOutcome.FAILED, FAILED_MESSAGE);
    }
}