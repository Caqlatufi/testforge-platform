package io.testforge.runorchestrator.service.quota;

import io.testforge.runorchestrator.run.entity.TestRunEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** 只供并发配额事务使用的 Run 仓储边界。 */
public interface RunQuotaRepository extends JpaRepository<TestRunEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from TestRunEntity run where run.id = :runId")
    Optional<TestRunEntity> findByIdForUpdate(@Param("runId") UUID runId);
}
