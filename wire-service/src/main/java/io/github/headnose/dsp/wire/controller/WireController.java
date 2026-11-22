package io.github.headnose.dsp.wire.controller;

import io.github.headnose.dsp.wire.controller.dto.SubmissionStatusDto;
import io.github.headnose.dsp.wire.controller.dto.WireGenerationRequestDto;
import io.github.headnose.dsp.wire.controller.dto.WireSubmissionDto;
import io.github.headnose.dsp.wire.entity.WireSubmission;
import io.github.headnose.dsp.wire.service.WireGenerationService;
import io.github.headnose.dsp.wire.service.WireSendService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wires")
public class WireController {

    private final WireGenerationService wireGenerationService;
    private final WireSendService wireSendService;

    public WireController(WireGenerationService wireGenerationService, WireSendService wireSendService) {
        this.wireGenerationService = wireGenerationService;
        this.wireSendService = wireSendService;
    }

    /** Generate Wire Statement (blueprint: POST /api/v1/wires). Creates a DRAFT submission. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WireSubmissionDto generate(@Valid @RequestBody WireGenerationRequestDto request) {
        WireSubmission submission = wireGenerationService.generate(request);
        return WireSubmissionDto.from(submission);
    }

    /**
     * Send Wire Statement (blueprint: POST /api/v1/wires/{submissionId}/send).
     * 202 Accepted (D11): the request is accepted and the emails go out asynchronously.
     */
    @PostMapping("/{submissionId}/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmissionStatusDto send(@PathVariable String submissionId) {
        return wireSendService.send(submissionId);
    }
}