package io.testforge.runorchestrator.task.repo;

import io.testforge.runorchestrator.task.entity.TestTaskEntity;
import io.testforge.runorchestrator.task.model.TaskState;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestTaskRepository extends JpaRepository<TestTaskEntity, UUID> {
    List<TestTaskEntity> findAllByDeploymentIdOrderBySequenceNoAsc(UUID deploymentId);

    @Query("select distinct task.deploymentId from TestTaskEntity task where task.deploymentId is not null")
    List<UUID> findDistinctDeploymentIds();

    List<TestTaskEntity> findAllByRunIdOrderBySequenceNoAsc(UUID runId);

    long countByRunId(UUID runId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from TestTaskEntity task where task.id = :taskId")
    Optional<TestTaskEntity> findByIdForUpdate(@Param("taskId") UUID taskId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update TestTaskEntity task
               set task.state = :targetState,
                   task.retryAvailableAt = :retryAvailableAt,
                   task.updatedAt = :transitionedAt,
                   task.completedAt = :completedAt,
                   task.persistenceVersion = task.persistenceVersion + 1
             where task.id = :taskId
               and task.state = :expectedState
               and task.persistenceVersion = :expectedVersion
            """)
    int compareAndSetReliabilityState(
            @Param("taskId") UUID taskId,
            @Param("expectedState") TaskState expectedState,
            @Param("expectedVersion") long expectedVersion,
            @Param("targetState") TaskState targetState,
            @Param("retryAvailableAt") Instant retryAvailableAt,
            @Param("transitionedAt") Instant transitionedAt,
            @Param("completedAt") Instant completedAt
    );

    /**
     * DAG 释放的原子边界。只有状态和持久化版本都仍与快照一致时才允许推进，
     * 从而让并发前置完成、重复回调和重试扫描最多释放后继一次。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update TestTaskEntity task
               set task.state = :targetState,
                   task.blockedByTaskId = :blockedByTaskId,
                   task.blockedReason = :blockedReason,
                   task.updatedAt = :transitionedAt,
                   task.completedAt = :completedAt,
                   task.persistenceVersion = task.persistenceVersion + 1
             where task.id = :taskId
               and task.runId = :runId
               and task.state = :expectedState
               and task.persistenceVersion = :expectedVersion
            """)
    int compareAndSetDagState(
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("expectedState") TaskState expectedState,
            @Param("expectedVersion") long expectedVersion,
            @Param("targetState") TaskState targetState,
            @Param("blockedByTaskId") UUID blockedByTaskId,
            @Param("blockedReason") String blockedReason,
            @Param("transitionedAt") Instant transitionedAt,
            @Param("completedAt") Instant completedAt
    );
}
