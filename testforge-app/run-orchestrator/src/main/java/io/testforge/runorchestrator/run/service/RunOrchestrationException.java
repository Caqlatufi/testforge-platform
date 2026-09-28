package io.testforge.runorchestrator.run.service;

public abstract class RunOrchestrationException extends RuntimeException {

    protected RunOrchestrationException(String message) {
        super(message);
    }

    protected RunOrchestrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
