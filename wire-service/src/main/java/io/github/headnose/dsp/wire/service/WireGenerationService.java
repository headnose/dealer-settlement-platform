package io.github.headnose.dsp.wire.service;

import io.github.headnose.dsp.wire.client.SettlementServiceClient;
import io.github.headnose.dsp.wire.client.dto.DealerDto;
import io.github.headnose.dsp.wire.client.dto.SettlementDetailDto;
import io.github.headnose.dsp.wire.controller.dto.WireGenerationRequestDto;
import io.github.headnose.dsp.wire.entity.NotificationDistribution;
import io.github.headnose.dsp.wire.entity.WireSubmission;
import io.github.headnose.dsp.wire.entity.WireTemplate;
import io.github.headnose.dsp.wire.model.DistributionType;
import io.github.headnose.dsp.wire.model.RecipientType;
import io.github.headnose.dsp.wire.repository.NotificationDistributionRepository;
import io.github.headnose.dsp.wire.repository.WireSubmissionRepository;
import io.github.headnose.dsp.wire.repository.WireTemplateRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Generates a settlement wire statement as a DRAFT submission (blueprint: Workflow 3).
 * P1: the settlement-service call is started first and runs while this thread does the
 * reference-data reads (blocking JDBC); the save needs the settlement detail, so it comes after.
 */
@Service
public class WireGenerationService {

    private final WireTemplateRepository templateRepository;
    private final NotificationDistributionRepository distributionRepository;
    private final WireSubmissionRepository submissionRepository;
    private final SettlementServiceClient settlementServiceClient;
    private final TemplateMerger templateMerger;
    private final Clock clock;

    public WireGenerationService(WireTemplateRepository templateRepository,
                                 NotificationDistributionRepository distributionRepository,
                                 WireSubmissionRepository submissionRepository,
                                 SettlementServiceClient settlementServiceClient,
                                 TemplateMerger templateMerger,
                                 Clock clock) {
        this.templateRepository = templateRepository;
        this.distributionRepository = distributionRepository;
        this.submissionRepository = submissionRepository;
        this.settlementServiceClient = settlementServiceClient;
        this.templateMerger = templateMerger;
        this.clock = clock;
    }

    /**
     * @throws io.github.headnose.dsp.wire.client.LoanNotImportedException             loan not in settlement-service
     * @throws io.github.headnose.dsp.wire.client.SettlementNotCalculatedException     settlement not calculated yet
     * @throws io.github.headnose.dsp.wire.client.SettlementServiceUnavailableException settlement-service unreachable
     * @throws IllegalStateException missing template or distribution (broken reference data)
     */
    public WireSubmission generate(WireGenerationRequestDto request) {
        // 1. Start the settlement-service call (non-blocking): it is in flight during steps 2 and 3
        CompletableFuture<SettlementDetailDto> detailFuture =
                settlementServiceClient.getSettlementDetailAsync(request.loanId());

        // 2 + 3. Reference data (MySQL, blocking JDBC) on this thread, overlapping with the HTTP call
        WireTemplate template;
        Map<DistributionType, NotificationDistribution> distributions;
        try {
            template = templateRepository
                    .findFirstByWireTypeOrderByVersionDesc(request.wireType())
                    .orElseThrow(() -> new IllegalStateException(
                            "No wire template configured for wire type " + request.wireType()));
            distributions = standingDistributions();
        } catch (RuntimeException brokenReferenceData) {
            // Same error as before (500); the settlement detail is not needed any more
            detailFuture.cancel(true);
            throw brokenReferenceData;
        }

        // 4. Wait for the settlement detail (bounded by the client's 2s connect / 5s response timeouts)
        SettlementDetailDto detail = await(detailFuture);

        // 5. Generate wire statement: merge template + settlement detail
        String content = templateMerger.merge(template.getTemplateContent(), templateValues(detail));
        WireSubmission submission = WireSubmission.draft(
                request.loanId(), request.wireType(), content, LocalDateTime.now(clock));

        // 6. Create recipients: dealer pair from the loan's dealer record, internal pair from the reference table
        DealerDto dealer = detail.loan().dealer();
        submission.addRecipient(RecipientType.DEALER,
                dealer.dealerName(), dealer.dealerContactEmail());
        submission.addRecipient(RecipientType.DEALER_BANK_CONTACT,
                dealer.dealerBankName(), dealer.dealerBankContactEmail());

        NotificationDistribution audit = distributions.get(DistributionType.AUDIT_DISTRIBUTION);
        NotificationDistribution ops = distributions.get(DistributionType.INTERNAL_OPS_DISTRIBUTION);
        submission.addRecipient(RecipientType.AUDIT_DISTRIBUTION, audit.getName(), audit.getEmail());
        submission.addRecipient(RecipientType.INTERNAL_OPS_DISTRIBUTION, ops.getName(), ops.getEmail());

        // 7. Save: one save() = one transaction for the submission and its 4 recipients (cascade)
        return submissionRepository.save(submission);
    }

    /** join() wraps the failure in CompletionException; unwrap it so callers see the same exceptions as before. */
    private static SettlementDetailDto await(CompletableFuture<SettlementDetailDto> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** Placeholder name -> value. Names match the {{...}} placeholders in the seeded template. */
    private static Map<String, String> templateValues(SettlementDetailDto detail) {
        Map<String, String> values = new HashMap<>();
        values.put("loanId", detail.loan().loanId());
        values.put("settlementId", detail.settlement().settlementId());
        values.put("dealerName", detail.loan().dealer().dealerName());
        values.put("beneficiaryBank", detail.payment().beneficiaryBank());
        values.put("paymentMethod", detail.payment().paymentMethod());
        values.put("currency", detail.payment().currency());
        values.put("grossAmount", detail.settlement().grossAmount().toPlainString());
        values.put("totalAdjustment", detail.settlement().totalAdjustment().toPlainString());
        values.put("netSettlement", detail.settlement().netSettlement().toPlainString());
        values.put("valueDate", detail.payment().valueDate().toString());
        return values;
    }

    /** Both standing distributions, by type; fails if the reference data is incomplete. */
    private Map<DistributionType, NotificationDistribution> standingDistributions() {
        Map<DistributionType, NotificationDistribution> byType = new EnumMap<>(DistributionType.class);
        for (NotificationDistribution distribution : distributionRepository.findAll()) {
            byType.put(distribution.getDistributionType(), distribution);
        }
        for (DistributionType type : DistributionType.values()) {
            if (!byType.containsKey(type)) {
                throw new IllegalStateException("No notification distribution configured for " + type);
            }
        }
        return byType;
    }
}