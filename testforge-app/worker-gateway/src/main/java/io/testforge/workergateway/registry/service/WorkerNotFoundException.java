package io.testforge.workergateway.registry.service;

public class WorkerNotFoundException extends WorkerRegistryException {

    public WorkerNotFoundException(String message) {
        super("RESOURCE_NOT_FOUND", message);
    }
}
