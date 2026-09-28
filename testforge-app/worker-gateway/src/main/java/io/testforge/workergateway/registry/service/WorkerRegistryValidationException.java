package io.testforge.workergateway.registry.service;

public class WorkerRegistryValidationException extends WorkerRegistryException {

    public WorkerRegistryValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }
}
