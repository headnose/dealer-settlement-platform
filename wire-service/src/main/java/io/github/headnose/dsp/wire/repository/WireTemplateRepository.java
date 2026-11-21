package io.github.headnose.dsp.wire.repository;

import io.github.headnose.dsp.wire.entity.WireTemplate;
import io.github.headnose.dsp.wire.model.WireType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WireTemplateRepository extends JpaRepository<WireTemplate, String> {

    /** Latest revision for a wire type (decision D4: highest version wins). */
    Optional<WireTemplate> findFirstByWireTypeOrderByVersionDesc(WireType wireType);
}