package io.github.headnose.dsp.wire.entity;

import io.github.headnose.dsp.wire.model.WireType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A wire template revision (blueprint: Wire Template). Reference data, seeded by Flyway. */
@Entity
@Table(name = "wire_template")
public class WireTemplate {

    @Id
    private String templateId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private WireType wireType;

    private String templateName;

    @Column(columnDefinition = "TEXT")
    private String templateContent;

    private int version;

    protected WireTemplate() {
        // for JPA
    }

    public String getTemplateId() { return templateId; }
    public WireType getWireType() { return wireType; }
    public String getTemplateName() { return templateName; }
    public String getTemplateContent() { return templateContent; }
    public int getVersion() { return version; }
}