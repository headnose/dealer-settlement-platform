package io.github.headnose.dsp.wire.repository;

import io.github.headnose.dsp.wire.entity.WireSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WireSubmissionRepository extends JpaRepository<WireSubmission, String> {
}