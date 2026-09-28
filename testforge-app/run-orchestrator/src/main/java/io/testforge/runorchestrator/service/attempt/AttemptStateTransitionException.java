package io.testforge.runorchestrator.service.attempt;

import io.testforge.runorchestrator.model.attempt.AttemptState;

public class AttemptStateTransitionException extends RuntimeException {

    public AttemptStateTransitionException(AttemptState source, AttemptState target) {
        super("Attempt 非法状态迁移: " + source + " -> " + target);
    }
}
