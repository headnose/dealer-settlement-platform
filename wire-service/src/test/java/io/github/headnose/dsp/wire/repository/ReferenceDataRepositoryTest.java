package io.github.headnose.dsp.wire.repository;

import io.github.headnose.dsp.wire.MySqlTestContainerConfig;
import io.github.headnose.dsp.wire.entity.NotificationDistribution;
import io.github.headnose.dsp.wire.entity.WireTemplate;
import io.github.headnose.dsp.wire.model.DistributionType;
import io.github.headnose.dsp.wire.model.WireType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(MySqlTestContainerConfig.class)
class ReferenceDataRepositoryTest {

    @Autowired
    private WireTemplateRepository templateRepository;

    @Autowired
    private NotificationDistributionRepository distributionRepository;

    @Test
    void latestSettlementTemplateIsSeeded() {
        WireTemplate template = templateRepository
                .findFirstByWireTypeOrderByVersionDesc(WireType.SETTLEMENT)
                .orElseThrow();

        assertThat(template.getTemplateId()).isEqualTo("TMP-001");
        assertThat(template.getVersion()).isEqualTo(1);
        assertThat(template.getTemplateContent()).contains("{{netSettlement}}");
    }

    @Test
    void bothStandingDistributionsAreSeeded() {
        assertThat(distributionRepository.findAll())
                .extracting(NotificationDistribution::getDistributionType)
                .containsExactlyInAnyOrder(DistributionType.values());
    }
}