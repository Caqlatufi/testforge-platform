package io.testforge.runorchestrator.service.reliability;

import io.testforge.runorchestrator.model.attempt.AttemptState;
import io.testforge.runorchestrator.task.model.TaskState;

import java.time.Instant;
import java.util.Objects;

/** dispatcher 决策后交回状态真相源原子提交的迁移。 */
public record ExecutionTransitionCommand(
        TaskState taskState,
        AttemptState attemptState,
        Instant transitionedAt,
        Instant retryAt,
        String reason
) {
    public ExecutionTransitionCommand {
        Objects.requireNonNull(taskState, "taskState 不能为空");
        Objects.requireNonNull(attemptState, "attemptState 不能为空");
        Objects.requireNonNull(transitionedAt, "transitionedAt 不能为空");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        if ((taskState == TaskState.QUEUED) != (retryAt != null)) {
            throw new IllegalArgumentException("只有重排到 QUEUED 时必须提供 retryAt");
        }
    }
}
