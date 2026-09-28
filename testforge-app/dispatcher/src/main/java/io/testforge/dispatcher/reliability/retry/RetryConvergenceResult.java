package io.testforge.dispatcher.reliability.retry;

import io.testforge.runorchestrator.model.attempt.AttemptState;
import io.testforge.runorchestrator.task.model.TaskState;

import java.time.Instant;
import java.util.Objects;

public record RetryConvergenceResult(
        RetryConvergenceOutcome outcome,
        RetryAction action,
        TaskState taskState,
        AttemptState attemptState,
        Instant retryAt,
        String reason
) {

    public RetryConvergenceResult {
        Objects.requireNonNull(outcome, "outcome 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        Objects.requireNonNull(taskState, "taskState 不能为空");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 不能为空");
        }
    }
}
