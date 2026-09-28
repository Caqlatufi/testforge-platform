package io.testforge.runorchestrator.task.model;

import java.util.EnumSet;
import java.util.Set;

public enum TaskState {
    CREATED,
    WAITING_DEPLOYMENT,
    WAITING_DEPENDENCY,
    QUEUED,
    DISPATCHED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    BLOCKED,
    TIMEOUT,
    CANCELLED;

    private static final Set<TaskState> TERMINAL = EnumSet.of(
            SUCCEEDED, FAILED, BLOCKED, TIMEOUT, CANCELLED
    );

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isSuccessful() {
        return this == SUCCEEDED;
    }

    public boolean hasStarted() {
        return this == DISPATCHED || this == RUNNING;
    }
}
