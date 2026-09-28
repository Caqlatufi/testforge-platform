package io.testforge.runorchestrator.run.service;

public final class RunStateConflictException extends RunOrchestrationException {

    public RunStateConflictException(String message) {
        super(message);
    }
}
