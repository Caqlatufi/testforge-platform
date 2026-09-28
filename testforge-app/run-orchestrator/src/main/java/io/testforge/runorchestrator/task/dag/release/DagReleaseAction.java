package io.testforge.runorchestrator.task.dag.release;

import io.testforge.runorchestrator.task.model.TaskState;

import java.util.Objects;
import java.util.UUID;

public record DagReleaseAction(
        UUID taskId,
        TaskState previousState,
        TaskState state,
        UUID blockedByTaskId,
        String blockedReason
) {

    public DagReleaseAction {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");
        Objects.requireNonNull(state, "state must not be null");
    }
}
