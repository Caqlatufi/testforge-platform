package io.testforge.runorchestrator.task.service;

import io.testforge.runorchestrator.task.model.TaskState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Task 的唯一合法迁移矩阵。 */
public final class TaskStateMachine {

    private static final Map<TaskState, Set<TaskState>> TRANSITIONS = transitions();

    public boolean canTransition(TaskState source, TaskState target) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        return source == target || TRANSITIONS.get(source).contains(target);
    }

    public void requireTransition(TaskState source, TaskState target) {
        if (!canTransition(source, target)) {
            throw new IllegalStateException("非法 Task 状态迁移: " + source + " -> " + target);
        }
    }

    private static Map<TaskState, Set<TaskState>> transitions() {
        EnumMap<TaskState, Set<TaskState>> transitions = new EnumMap<>(TaskState.class);
        transitions.put(TaskState.CREATED, Set.copyOf(EnumSet.of(
                TaskState.WAITING_DEPLOYMENT, TaskState.WAITING_DEPENDENCY, TaskState.QUEUED, TaskState.CANCELLED
        )));
        transitions.put(TaskState.WAITING_DEPLOYMENT, Set.copyOf(EnumSet.of(
                TaskState.WAITING_DEPENDENCY, TaskState.QUEUED, TaskState.BLOCKED, TaskState.CANCELLED
        )));
        transitions.put(TaskState.WAITING_DEPENDENCY, Set.copyOf(EnumSet.of(
                TaskState.WAITING_DEPLOYMENT, TaskState.QUEUED, TaskState.BLOCKED, TaskState.CANCELLED
        )));
        transitions.put(TaskState.QUEUED, Set.copyOf(EnumSet.of(
                TaskState.DISPATCHED, TaskState.CANCELLED
        )));
        transitions.put(TaskState.DISPATCHED, Set.copyOf(EnumSet.of(
                TaskState.RUNNING, TaskState.QUEUED, TaskState.CANCELLED
        )));
        transitions.put(TaskState.RUNNING, Set.copyOf(EnumSet.of(
                TaskState.SUCCEEDED,
                TaskState.FAILED,
                TaskState.QUEUED,
                TaskState.TIMEOUT,
                TaskState.CANCELLED
        )));
        transitions.put(TaskState.TIMEOUT, Set.copyOf(EnumSet.of(
                TaskState.QUEUED, TaskState.CANCELLED
        )));
        for (TaskState state : TaskState.values()) {
            transitions.putIfAbsent(state, Set.of());
        }
        return Map.copyOf(transitions);
    }
}
