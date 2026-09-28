package io.testforge.runorchestrator.run.repo;

import io.testforge.runorchestrator.run.entity.TestRunEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface TestRunRepository extends JpaRepository<TestRunEntity, UUID> {

    Optional<TestRunEntity> findByRequestKey(UUID requestKey);

    List<TestRunEntity> findAllByOrderByCreatedAtDesc();
    List<TestRunEntity> findAllByTestJobIdOrderByCreatedAtDesc(UUID testJobId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from TestRunEntity run where run.id = :runId")
    Optional<TestRunEntity> findByIdForUpdate(@Param("runId") UUID runId);
}
