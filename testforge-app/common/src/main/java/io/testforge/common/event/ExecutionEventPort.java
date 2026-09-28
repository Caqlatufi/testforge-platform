package io.testforge.common.event;

@FunctionalInterface
public interface ExecutionEventPort {
    void append(ExecutionEventCommand command);

    static ExecutionEventPort noop() {
        return command -> { };
    }
}
