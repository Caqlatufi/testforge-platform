package io.testforge.runorchestrator.model.attempt;

/**
 * 一次 Task 执行尝试的生命周期状态。
 */
public enum AttemptState {
    CREATED(false),
    RUNNING(false),
    SUCCEEDED(true),
    FAILED(true),
    CANCELLED(true),
    TIMEOUT(true),
    LOST(true);

    private final boolean terminal;

    AttemptState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
