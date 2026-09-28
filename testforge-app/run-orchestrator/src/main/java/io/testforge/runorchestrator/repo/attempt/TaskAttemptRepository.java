package io.testforge.runorchestrator.repo.attempt;

import io.testforge.runorchestrator.entity.attempt.TaskAttemptEntity;
import io.testforge.runorchestrator.model.attempt.AttemptState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskAttemptRepository extends JpaRepository<TaskAttemptEntity, UUID> {

    Optional<TaskAttemptEntity> findByTaskIdAndAttemptNo(UUID taskId, int attemptNo);

    Optional<TaskAttemptEntity> findTopByTaskIdOrderByAttemptNoDesc(UUID taskId);

    @Query(value = """
            select attempt.*
              from run_orchestrator_task_attempt attempt
              join run_orchestrator_task task on task.id = attempt.task_id
             where attempt.state = :attemptState
               and task.state in (:taskStates)
               and not exists (
                    select 1 from run_orchestrator_task_attempt newer
                     where newer.task_id = attempt.task_id
                       and newer.attempt_no > attempt.attempt_no
               )
             order by attempt.created_at asc, attempt.id asc
            """, nativeQuery = true)
    List<TaskAttemptEntity> findRecoveryCandidates(
            @Param("attemptState") String attemptState,
            @Param("taskStates") Collection<String> taskStates,
            Pageable pageable
    );

    @Query("""
            select attempt from TaskAttemptEntity attempt
             where attempt.state = io.testforge.runorchestrator.model.attempt.AttemptState.RUNNING
               and attempt.leaseUntil <= :expiredAtOrBefore
             order by attempt.leaseUntil asc, attempt.id asc
            """)
    List<TaskAttemptEntity> findExpiredRunning(
            @Param("expiredAtOrBefore") Instant expiredAtOrBefore,
            Pageable pageable
    );

    List<TaskAttemptEntity> findAllByTaskIdInOrderByTaskIdAscAttemptNoAsc(Collection<UUID> taskIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update TaskAttemptEntity attempt
               set attempt.state = :targetState,
                   attempt.version = attempt.version + 1,
                   attempt.updatedAt = :transitionedAt
             where attempt.id = :attemptId
               and attempt.state = :expectedState
               and attempt.version = :expectedVersion
            """)
    int compareAndSetState(
            @Param("attemptId") UUID attemptId,
            @Param("expectedState") AttemptState expectedState,
            @Param("expectedVersion") long expectedVersion,
            @Param("targetState") AttemptState targetState,
            @Param("transitionedAt") Instant transitionedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update TaskAttemptEntity attempt
               set attempt.leaseUntil = :extendedUntil,
                   attempt.version = attempt.version + 1,
                   attempt.updatedAt = :acceptedAt
             where attempt.id = :attemptId
               and attempt.workerId = :workerId
               and attempt.leaseToken = :leaseToken
               and attempt.state = io.testforge.runorchestrator.model.attempt.AttemptState.RUNNING
               and attempt.leaseUntil >= :acceptedAt
            """)
    int heartbeat(
            @Param("attemptId") UUID attemptId,
            @Param("workerId") String workerId,
            @Param("leaseToken") UUID leaseToken,
            @Param("acceptedAt") Instant acceptedAt,
            @Param("extendedUntil") Instant extendedUntil
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update TaskAttemptEntity attempt
               set attempt.state = io.testforge.runorchestrator.model.attempt.AttemptState.LOST,
                   attempt.version = attempt.version + 1,
                   attempt.updatedAt = :detectedAt
             where attempt.id = :attemptId
               and attempt.leaseToken = :leaseToken
               and attempt.version = :expectedVersion
               and attempt.state = io.testforge.runorchestrator.model.attempt.AttemptState.RUNNING
               and attempt.leaseUntil <= :detectedAt
            """)
    int markLostIfExpired(
            @Param("attemptId") UUID attemptId,
            @Param("leaseToken") UUID leaseToken,
            @Param("expectedVersion") long expectedVersion,
            @Param("detectedAt") Instant detectedAt
    );
}
