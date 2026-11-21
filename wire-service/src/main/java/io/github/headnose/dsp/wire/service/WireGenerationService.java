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

/** Generates a settlement wire statement as a DRAFT submission (blueprint: Workflow 3). */
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
        // 1. Load wire template (MySQL)
        WireTemplate template = templateRepository
                .findFirstByWireTypeOrderByVersionDesc(request.wireType())
                .orElseThrow(() -> new IllegalStateException(
                        "No wire template configured for wire type " + request.wireType()));

        // 2. Retrieve settlement information (settlement-service)
        SettlementDetailDto detail = settlementServiceClient.getSettlementDetail(request.loanId());

        // 3. Generate wire statement: merge template + settlement detail
        String content = templateMerger.merge(template.getTemplateContent(), templateValues(detail));
        WireSubmission submission = WireSubmission.draft(
                request.loanId(), request.wireType(), content, LocalDateTime.now(clock));

        // 4. Create recipients: dealer pair from the loan's dealer record, internal pair from the reference table
        DealerDto dealer = detail.loan().dealer();
        submission.addRecipient(RecipientType.DEALER,
                dealer.dealerName(), dealer.dealerContactEmail());
        submission.addRecipient(RecipientType.DEALER_BANK_CONTACT,
                dealer.dealerBankName(), dealer.dealerBankContactEmail());

        Map<DistributionType, NotificationDistribution> distributions = standingDistributions();
        NotificationDistribution audit = distributions.get(DistributionType.AUDIT_DISTRIBUTION);
        NotificationDistribution ops = distributions.get(DistributionType.INTERNAL_OPS_DISTRIBUTION);
        submission.addRecipient(RecipientType.AUDIT_DISTRIBUTION, audit.getName(), audit.getEmail());
        submission.addRecipient(RecipientType.INTERNAL_OPS_DISTRIBUTION, ops.getName(), ops.getEmail());

        // 5. Save: one save() = one transaction for the submission and its 4 recipients (cascade)
        return submissionRepository.save(submission);
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