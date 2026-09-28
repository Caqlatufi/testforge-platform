package io.testforge.runorchestrator.run.model;

import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.model.TaskView;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;

public record RunView(
        UUID id,
        UUID projectId,
        UUID targetId,
        UUID environmentId,
        UUID workflowId,
        int workflowVersion,
        String workflowChecksum,
        RunState state,
        int priority,
        int maxConcurrency,
        int processConcurrency,
        int deviceConcurrency,
        UUID requestKey,
        UUID comparisonGroupId,
        long version,
        Instant cancellationRequestedAt,
        String cancellationReason,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Map<TaskState, Long> taskCounts,
        List<TaskView> tasks,
        UUID testJobId,
        long jobConfigVersion,
        String testJobSnapshot
) {
    public RunView {
        taskCounts = Map.copyOf(taskCounts);
        tasks = List.copyOf(tasks);
    }

    public RunView(
            UUID id, UUID projectId, UUID targetId, UUID environmentId, UUID workflowId,
            int workflowVersion, String workflowChecksum, RunState state, int priority,
            int maxConcurrency, int processConcurrency, int deviceConcurrency, UUID requestKey,
            long version, Instant cancellationRequestedAt, String cancellationReason,
            Instant createdAt, Instant updatedAt, Instant completedAt,
            Map<TaskState, Long> taskCounts, List<TaskView> tasks
    ) {
        this(id, projectId, targetId, environmentId, workflowId, workflowVersion, workflowChecksum,
                state, priority, maxConcurrency, processConcurrency, deviceConcurrency, requestKey, null,
                version, cancellationRequestedAt, cancellationReason, createdAt, updatedAt, completedAt,
                taskCounts, tasks, null, 0, null);
    }

    public RunView(
            UUID id, UUID projectId, UUID targetId, UUID environmentId, UUID workflowId,
            int workflowVersion, String workflowChecksum, RunState state, int priority,
            int maxConcurrency, UUID requestKey, long version, Instant cancellationRequestedAt,
            String cancellationReason, Instant createdAt, Instant updatedAt, Instant completedAt,
            Map<TaskState, Long> taskCounts, List<TaskView> tasks
    ) {
        this(id, projectId, targetId, environmentId, workflowId, workflowVersion, workflowChecksum,
                state, priority, maxConcurrency, maxConcurrency, maxConcurrency, requestKey, null, version,
                cancellationRequestedAt, cancellationReason, createdAt, updatedAt, completedAt,
                taskCounts, tasks, null, 0, null);
    }
}
