package io.testforge.runorchestrator.run.model;

public enum RunState {
    QUEUED,
    RUNNING,
    CANCELLING,
    SUCCEEDED,
    FAILED,
    COMPLETED_WITH_WARNINGS,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED
                || this == FAILED
                || this == COMPLETED_WITH_WARNINGS
                || this == CANCELLED;
    }
}
