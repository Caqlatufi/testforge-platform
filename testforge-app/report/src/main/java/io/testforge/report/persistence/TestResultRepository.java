package io.testforge.report.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TestResultRepository extends JpaRepository<TestResultEntity, UUID> {
    List<TestResultEntity> findAllByTaskIdIn(Collection<UUID> taskIds);
    List<TestResultEntity> findAllByRunIdOrderByRecordedAtAsc(UUID runId);
    List<TestResultEntity> findTop10ByCaseIdOrderByRecordedAtDesc(UUID caseId);
}
