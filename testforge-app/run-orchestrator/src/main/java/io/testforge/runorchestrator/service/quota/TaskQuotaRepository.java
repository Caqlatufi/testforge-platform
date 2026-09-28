package io.testforge.runorchestrator.service.quota;

import io.testforge.runorchestrator.task.entity.TestTaskEntity;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.model.ResourceMode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 只供并发配额事务使用的 Task 仓储边界。 */
public interface TaskQuotaRepository extends JpaRepository<TestTaskEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from TestTaskEntity task where task.id = :taskId")
    Optional<TestTaskEntity> findByIdForUpdate(@Param("taskId") UUID taskId);

    long countByRunIdAndStateIn(UUID runId, Collection<TaskState> states);

    long countByRunIdAndResourceModeAndStateIn(
            UUID runId,
            ResourceMode resourceMode,
            Collection<TaskState> states
    );

    List<TestTaskEntity> findAllByRunIdOrderBySequenceNoAsc(UUID runId);
}
