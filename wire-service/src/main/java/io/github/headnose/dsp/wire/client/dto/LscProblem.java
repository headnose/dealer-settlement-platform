package io.github.headnose.dsp.wire.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The parts of settlement-service's RFC 9457 ProblemDetail that wire-service reads. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LscProblem(String title, String detail) {}