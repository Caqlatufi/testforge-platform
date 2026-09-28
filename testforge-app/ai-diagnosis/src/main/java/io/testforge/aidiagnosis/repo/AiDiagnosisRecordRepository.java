package io.testforge.aidiagnosis.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiDiagnosisRecordRepository extends JpaRepository<AiDiagnosisRecordEntity, UUID> {
    Optional<AiDiagnosisRecordEntity> findByReportIdAndRequestKey(UUID reportId, UUID requestKey);
    Optional<AiDiagnosisRecordEntity> findFirstByReportIdAndStatusOrderByCompletedAtDesc(UUID reportId, String status);
    Optional<AiDiagnosisRecordEntity> findFirstByReportIdAndEvidenceHashAndStatusOrderByCompletedAtDesc(
            UUID reportId, String evidenceHash, String status);
}
