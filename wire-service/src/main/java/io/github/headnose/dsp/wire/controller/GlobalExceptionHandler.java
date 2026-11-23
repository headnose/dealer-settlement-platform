package io.github.headnose.dsp.wire.controller;

import io.github.headnose.dsp.wire.client.LoanNotImportedException;
import io.github.headnose.dsp.wire.client.SettlementNotCalculatedException;
import io.github.headnose.dsp.wire.client.SettlementServiceUnavailableException;
import io.github.headnose.dsp.wire.messaging.EmailDeliveryPublishException;
import io.github.headnose.dsp.wire.service.DuplicateSubmissionException;
import io.github.headnose.dsp.wire.service.SubmissionNotFoundException;
import io.github.headnose.dsp.wire.service.SubmissionNotSendableException;
import io.github.headnose.dsp.wire.storage.WireStatementStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Turns exceptions into RFC 9457 Problem Details (decisions D3, D11, D13, D23). */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final HttpStatusCode UNPROCESSABLE = HttpStatusCode.valueOf(422);

    // ---------- 422: the loan is not ready for a wire ----------

    @ExceptionHandler(LoanNotImportedException.class)
    public ProblemDetail handleLoanNotImported(LoanNotImportedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(UNPROCESSABLE, e.getMessage());
        problem.setTitle("Loan not imported");
        problem.setProperty("loanId", e.getLoanId());
        return problem;
    }

    @ExceptionHandler(SettlementNotCalculatedException.class)
    public ProblemDetail handleSettlementNotCalculated(SettlementNotCalculatedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(UNPROCESSABLE, e.getMessage());
        problem.setTitle("Settlement not calculated");
        problem.setProperty("loanId", e.getLoanId());
        return problem;
    }

    // ---------- 404 / 409: sending a submission (D11, D12) ----------

    @ExceptionHandler(SubmissionNotFoundException.class)
    public ProblemDetail handleSubmissionNotFound(SubmissionNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Submission not found");
        return problem;
    }

    @ExceptionHandler(SubmissionNotSendableException.class)
    public ProblemDetail handleSubmissionNotSendable(SubmissionNotSendableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Submission not sendable");
        return problem;
    }

    @ExceptionHandler(DuplicateSubmissionException.class)
    public ProblemDetail handleDuplicateSubmission(DuplicateSubmissionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Wire already sent");
        return problem;
    }

    // ---------- 502: settlement-service did not answer ----------

    @ExceptionHandler(SettlementServiceUnavailableException.class)
    public ProblemDetail handleSettlementServiceUnavailable(SettlementServiceUnavailableException e) {
        log.warn("settlement-service call failed: {}", e.getMessage(), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "Could not get the settlement from settlement-service. Try again later.");
        problem.setTitle("Settlement service unavailable");
        return problem;
    }

    // ---------- 503: Kafka did not confirm the events (D13) ----------

    @ExceptionHandler(EmailDeliveryPublishException.class)
    public ProblemDetail handleEmailDeliveryUnavailable(EmailDeliveryPublishException e) {
        log.warn("email-delivery publish failed: {}", e.getMessage(), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not queue the wire for delivery. It is still a DRAFT; try again later.");
        problem.setTitle("Email delivery unavailable");
        return problem;
    }

    // ---------- 503: the wire statement could not be stored (D23) ----------

    @ExceptionHandler(WireStatementStorageException.class)
    public ProblemDetail handleWireStatementStorageUnavailable(WireStatementStorageException e) {
        log.warn("wire statement upload failed: {}", e.getMessage(), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not store the wire statement. It is still a DRAFT; try again later.");
        problem.setTitle("Wire statement storage unavailable");
        return problem;
    }

    // ---------- 400: invalid request fields ----------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setTitle("Validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid")))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    // ---------- 500: anything else (logged, never returned) ----------

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred.");
    }
}