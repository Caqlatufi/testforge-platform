package io.testforge.testjob.repo;

import io.testforge.testjob.entity.TestJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestJobRepository extends JpaRepository<TestJobEntity, UUID> {
    Optional<TestJobEntity> findByProjectIdAndCode(UUID projectId, String code);
    List<TestJobEntity> findAllByProjectIdOrderByCreatedAtAsc(UUID projectId);
    List<TestJobEntity> findAllByProjectIdOrderByCreatedAtDesc(UUID projectId);
    List<TestJobEntity> findAllByOrderByCreatedAtDesc();
}
