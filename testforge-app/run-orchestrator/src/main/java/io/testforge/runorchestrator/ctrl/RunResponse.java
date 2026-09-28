package io.testforge.runorchestrator.ctrl;

import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.run.model.RunView;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.model.TaskView;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 公开 Run 聚合。前六个字段满足 OpenAPI RunSummary，附加字段用于运行详情与 Attempt 时间线。
 */
public record RunResponse(
        UUID id,
        RunState state,
        int totalTasks,
        Map<TaskState, Long> taskCounts,
        Instant createdAt,
        Instant finishedAt,
        UUID projectId,
        UUID targetId,
        UUID environmentId,
        UUID workflowId,
        int workflowVersion,
        String workflowChecksum,
        int priority,
        int maxConcurrency,
        int processConcurrency,
        int deviceConcurrency,
        UUID requestKey,
        UUID comparisonGroupId,
        long version,
        Instant cancellationRequestedAt,
        String cancellationReason,
        Instant updatedAt,
        List<TaskView> tasks,
        UUID testJobId,
        long jobConfigVersion,
        String testJobSnapshot
) {

    public RunResponse {
        taskCounts = Map.copyOf(taskCounts);
        tasks = List.copyOf(tasks);
    }

    public static RunResponse from(RunView view) {
        return new RunResponse(
                view.id(),
                view.state(),
                view.tasks().size(),
                view.taskCounts(),
                view.createdAt(),
                view.completedAt(),
                view.projectId(),
                view.targetId(),
                view.environmentId(),
                view.workflowId(),
                view.workflowVersion(),
                view.workflowChecksum(),
                view.priority(),
                view.maxConcurrency(),
                view.processConcurrency(),
                view.deviceConcurrency(),
                view.requestKey(),
                view.comparisonGroupId(),
                view.version(),
                view.cancellationRequestedAt(),
                view.cancellationReason(),
                view.updatedAt(),
                view.tasks(),
                view.testJobId(),
                view.jobConfigVersion(),
                view.testJobSnapshot()
        );
    }
}
