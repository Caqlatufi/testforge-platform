package io.testforge.runorchestrator.run.service;

import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.task.entity.TestTaskEntity;
import io.testforge.runorchestrator.task.model.TaskState;

import java.util.List;
import java.util.Objects;

/**
 * Run 结论只由持久化 Task 状态收敛；非必需任务失败保留告警，但不冒充整体失败。
 */
public final class RunAggregationPolicy {

    public RunState determine(boolean cancellationRequested, List<TestTaskEntity> tasks) {
        Objects.requireNonNull(tasks, "tasks must not be null");
        if (cancellationRequested) {
            return tasks.stream().allMatch(task -> task.getState().isTerminal())
                    ? RunState.CANCELLED
                    : RunState.CANCELLING;
        }
        if (tasks.isEmpty()) {
            return RunState.SUCCEEDED;
        }
        if (tasks.stream().anyMatch(task -> !task.getState().isTerminal())) {
            boolean executionStarted = tasks.stream()
                    .map(TestTaskEntity::getState)
                    .anyMatch(state -> state == TaskState.DISPATCHED || state == TaskState.RUNNING);
            return executionStarted ? RunState.RUNNING : RunState.QUEUED;
        }

        boolean requiredFailure = tasks.stream()
                .anyMatch(task -> task.isRequired() && !task.getState().isSuccessful());
        if (requiredFailure) {
            return RunState.FAILED;
        }
        boolean optionalFailure = tasks.stream()
                .anyMatch(task -> !task.isRequired() && !task.getState().isSuccessful());
        return optionalFailure ? RunState.COMPLETED_WITH_WARNINGS : RunState.SUCCEEDED;
    }
}
