package io.testforge.workergateway.registry.service;

public abstract class WorkerRegistryException extends RuntimeException {

    private final String code;

    protected WorkerRegistryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
