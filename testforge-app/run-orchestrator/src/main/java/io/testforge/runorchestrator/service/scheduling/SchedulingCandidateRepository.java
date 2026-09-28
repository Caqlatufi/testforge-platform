package io.testforge.runorchestrator.service.scheduling;

import io.testforge.runorchestrator.task.entity.TestTaskEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

/** 调度器的只读候选视图；最终领取仍由配额事务二次校验。 */
public interface SchedulingCandidateRepository extends Repository<TestTaskEntity, UUID> {

    @Query("""
            select new io.testforge.runorchestrator.service.scheduling.SchedulingCandidate(
                    task.id,
                    task.runId,
                    run.priority,
                    task.sequenceNo,
                    task.updatedAt
            )
              from TestTaskEntity task, TestRunEntity run
             where task.runId = run.id
               and task.state = io.testforge.runorchestrator.task.model.TaskState.QUEUED
               and (task.retryAvailableAt is null or task.retryAvailableAt <= :scheduledAt)
               and run.cancellationRequestedAt is null
               and run.state in (
                    io.testforge.runorchestrator.run.model.RunState.QUEUED,
                    io.testforge.runorchestrator.run.model.RunState.RUNNING
               )
            """)
    List<SchedulingCandidate> findQueuedCandidates(@Param("scheduledAt") Instant scheduledAt);
}
