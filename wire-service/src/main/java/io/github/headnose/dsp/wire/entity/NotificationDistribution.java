package io.github.headnose.dsp.wire.entity;

import io.github.headnose.dsp.wire.model.DistributionType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A standing internal recipient (blueprint: Notification Distribution). Reference data, seeded by Flyway. */
@Entity
@Table(name = "notification_distribution")
public class NotificationDistribution {

    @Id
    private String distributionId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private DistributionType distributionType;

    private String name;

    private String email;

    protected NotificationDistribution() {
        // for JPA
    }

    public String getDistributionId() { return distributionId; }
    public DistributionType getDistributionType() { return distributionType; }
    public String getName() { return name; }
    public String getEmail() { return email; }
}