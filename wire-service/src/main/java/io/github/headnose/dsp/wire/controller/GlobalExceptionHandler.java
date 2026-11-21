package io.github.headnose.dsp.wire.controller;

import io.github.headnose.dsp.wire.client.LoanNotImportedException;
import io.github.headnose.dsp.wire.client.SettlementNotCalculatedException;
import io.github.headnose.dsp.wire.client.SettlementServiceUnavailableException;
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

/** Turns exceptions into RFC 9457 Problem Details (decision D3). */
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

    // ---------- 502: settlement-service did not answer ----------

    @ExceptionHandler(SettlementServiceUnavailableException.class)
    public ProblemDetail handleSettlementServiceUnavailable(SettlementServiceUnavailableException e) {
        log.warn("settlement-service call failed: {}", e.getMessage(), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "Could not get the settlement from settlement-service. Try again later.");
        problem.setTitle("Settlement service unavailable");
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