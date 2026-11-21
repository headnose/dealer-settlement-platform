package io.github.headnose.dsp.wire.repository;

import io.github.headnose.dsp.wire.entity.NotificationDistribution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDistributionRepository extends JpaRepository<NotificationDistribution, String> {
}