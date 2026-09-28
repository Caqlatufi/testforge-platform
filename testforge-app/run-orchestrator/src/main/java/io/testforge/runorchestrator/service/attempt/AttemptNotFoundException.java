package io.testforge.runorchestrator.service.attempt;

import java.util.UUID;

public class AttemptNotFoundException extends RuntimeException {

    public AttemptNotFoundException(UUID attemptId) {
        super("Attempt 不存在: " + attemptId);
    }
}
