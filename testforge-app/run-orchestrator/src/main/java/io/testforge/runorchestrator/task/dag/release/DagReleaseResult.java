package io.testforge.runorchestrator.task.dag.release;

import io.testforge.runorchestrator.task.model.TaskState;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DagReleaseResult(List<DagReleaseAction> actions) {

    public DagReleaseResult {
        actions = List.copyOf(Objects.requireNonNull(actions, "actions must not be null"));
    }

    public List<UUID> releasedTaskIds() {
        return taskIdsInState(TaskState.QUEUED);
    }

    public List<UUID> blockedTaskIds() {
        return taskIdsInState(TaskState.BLOCKED);
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }

    private List<UUID> taskIdsInState(TaskState state) {
        return actions.stream()
                .filter(action -> action.state() == state)
                .map(DagReleaseAction::taskId)
                .toList();
    }
}
