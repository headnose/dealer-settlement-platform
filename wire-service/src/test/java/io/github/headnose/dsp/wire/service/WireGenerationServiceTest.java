package io.github.headnose.dsp.wire.service;

import io.github.headnose.dsp.wire.client.LoanNotImportedException;
import io.github.headnose.dsp.wire.client.SettlementServiceClient;
import io.github.headnose.dsp.wire.client.dto.AdjustmentDto;
import io.github.headnose.dsp.wire.client.dto.DealerDto;
import io.github.headnose.dsp.wire.client.dto.LoanDto;
import io.github.headnose.dsp.wire.client.dto.PaymentDto;
import io.github.headnose.dsp.wire.client.dto.SettlementDetailDto;
import io.github.headnose.dsp.wire.client.dto.SettlementDto;
import io.github.headnose.dsp.wire.controller.dto.WireGenerationRequestDto;
import io.github.headnose.dsp.wire.entity.NotificationDistribution;
import io.github.headnose.dsp.wire.entity.WireRecipient;
import io.github.headnose.dsp.wire.entity.WireSubmission;
import io.github.headnose.dsp.wire.entity.WireTemplate;
import io.github.headnose.dsp.wire.model.DistributionType;
import io.github.headnose.dsp.wire.model.RecipientType;
import io.github.headnose.dsp.wire.model.SubmissionStatus;
import io.github.headnose.dsp.wire.model.WireType;
import io.github.headnose.dsp.wire.repository.NotificationDistributionRepository;
import io.github.headnose.dsp.wire.repository.WireSubmissionRepository;
import io.github.headnose.dsp.wire.repository.WireTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WireGenerationServiceTest {

    private static final String LOAN_ID = "LN-1001";
    private static final WireGenerationRequestDto REQUEST =
            new WireGenerationRequestDto(LOAN_ID, WireType.SETTLEMENT);

    /** 19:30:25.123 UTC = 14:30:25.123 in New York (EST, UTC-5). */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-11-21T19:30:25.123Z"), ZoneId.of("America/New_York"));

    @Mock
    private WireTemplateRepository templateRepository;
    @Mock
    private NotificationDistributionRepository distributionRepository;
    @Mock
    private WireSubmissionRepository submissionRepository;
    @Mock
    private SettlementServiceClient settlementServiceClient;

    private WireGenerationService service;

    @BeforeEach
    void setUp() {
        service = new WireGenerationService(templateRepository, distributionRepository,
                submissionRepository, settlementServiceClient, new TemplateMerger(), FIXED_CLOCK);
    }

    @Test
    void generatesDraftWithMergedContentAndFourRecipients() {
        WireTemplate template = stubTemplateLookup();
        when(template.getTemplateContent()).thenReturn(
                "<wire><dealer>{{dealerName}}</dealer><net>{{netSettlement}}</net>"
                        + "<valueDate>{{valueDate}}</valueDate></wire>");
        when(settlementServiceClient.getSettlementDetailAsync(LOAN_ID))
                .thenReturn(CompletableFuture.completedFuture(settlementDetail()));
        stubStandingDistributions();
        when(submissionRepository.save(any(WireSubmission.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WireSubmission submission = service.generate(REQUEST);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.DRAFT);
        assertThat(submission.getLoanId()).isEqualTo(LOAN_ID);
        assertThat(submission.getCreatedTime()).isEqualTo(LocalDateTime.of(2026, 11, 21, 14, 30, 25));
        assertThat(submission.getContent()).isEqualTo(
                "<wire><dealer>Riverbend Motors</dealer><net>27850.00</net>"
                        + "<valueDate>2026-11-23</valueDate></wire>");
        assertThat(submission.getRecipients())
                .extracting(WireRecipient::getRecipientType, WireRecipient::getName, WireRecipient::getEmail)
                .containsExactly(
                        tuple(RecipientType.DEALER,
                                "Riverbend Motors", "accounts@riverbend-motors.example.com"),
                        tuple(RecipientType.DEALER_BANK_CONTACT,
                                "Riverbend Community Bank", "wires@riverbend-bank.example.com"),
                        tuple(RecipientType.AUDIT_DISTRIBUTION,
                                "Compliance Audit Team", "compliance-audit@example.com"),
                        tuple(RecipientType.INTERNAL_OPS_DISTRIBUTION,
                                "Treasury Operations", "treasury-ops@example.com"));
        verify(submissionRepository).save(submission);
    }

    @Test
    void savesNothingAndRethrowsTheClientExceptionWhenLoanIsNotImported() {
        stubTemplateLookup();
        stubStandingDistributions();
        when(settlementServiceClient.getSettlementDetailAsync(LOAN_ID))
                .thenReturn(CompletableFuture.failedFuture(new LoanNotImportedException(LOAN_ID)));

        // Unwrapped: the same exception type as the old blocking client, not a CompletionException
        assertThatThrownBy(() -> service.generate(REQUEST))
                .isInstanceOf(LoanNotImportedException.class);

        verifyNoInteractions(submissionRepository);
    }

    @Test
    void cancelsTheSettlementCallWhenNoTemplateExists() {
        CompletableFuture<SettlementDetailDto> inFlight = new CompletableFuture<>(); // never completes
        when(settlementServiceClient.getSettlementDetailAsync(LOAN_ID)).thenReturn(inFlight);
        when(templateRepository.findFirstByWireTypeOrderByVersionDesc(WireType.SETTLEMENT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(REQUEST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SETTLEMENT");

        assertThat(inFlight).isCancelled();
        verifyNoInteractions(submissionRepository);
    }

    // ---------- helpers ----------

    private WireTemplate stubTemplateLookup() {
        WireTemplate template = mock(WireTemplate.class);
        when(templateRepository.findFirstByWireTypeOrderByVersionDesc(WireType.SETTLEMENT))
                .thenReturn(Optional.of(template));
        return template;
    }

    /** Both distributions; built before thenReturn (never create mocks inside another stubbing). */
    private void stubStandingDistributions() {
        NotificationDistribution audit = distribution(DistributionType.AUDIT_DISTRIBUTION,
                "Compliance Audit Team", "compliance-audit@example.com");
        NotificationDistribution ops = distribution(DistributionType.INTERNAL_OPS_DISTRIBUTION,
                "Treasury Operations", "treasury-ops@example.com");
        when(distributionRepository.findAll()).thenReturn(List.of(audit, ops));
    }

    /** lenient(): tests that fail before the recipients are built never read name/email. */
    private static NotificationDistribution distribution(DistributionType type, String name, String email) {
        NotificationDistribution distribution = mock(NotificationDistribution.class);
        lenient().when(distribution.getDistributionType()).thenReturn(type);
        lenient().when(distribution.getName()).thenReturn(name);
        lenient().when(distribution.getEmail()).thenReturn(email);
        return distribution;
    }

    private static SettlementDetailDto settlementDetail() {
        return new SettlementDetailDto(
                new LoanDto(LOAN_ID,
                        new DealerDto("D-100", "Riverbend Motors", "Riverbend Community Bank",
                                "accounts@riverbend-motors.example.com", "wires@riverbend-bank.example.com"),
                        "CLOSED", "USD", new BigDecimal("28500.00"), LocalDate.of(2026, 10, 15)),
                new SettlementDto("SET-123", new BigDecimal("28500.00"), new BigDecimal("650.00"),
                        new BigDecimal("27850.00"), "CALCULATED"),
                new PaymentDto("WIRE", "USD", "Riverbend Community Bank", LocalDate.of(2026, 11, 23)),
                List.of(new AdjustmentDto("DEALER_HOLDBACK", new BigDecimal("500.00")),
                        new AdjustmentDto("DOC_FEE", new BigDecimal("150.00"))));
    }
}