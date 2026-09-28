package io.testforge.runorchestrator.run.service;

public final class RunIdempotencyConflictException extends RunOrchestrationException {

    public RunIdempotencyConflictException(String message) {
        super(message);
    }
}
