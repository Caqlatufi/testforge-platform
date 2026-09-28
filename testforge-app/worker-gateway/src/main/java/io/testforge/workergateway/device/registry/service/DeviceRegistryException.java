package io.testforge.workergateway.device.registry.service;

public abstract class DeviceRegistryException extends RuntimeException {

    protected DeviceRegistryException(String message) {
        super(message);
    }
}
