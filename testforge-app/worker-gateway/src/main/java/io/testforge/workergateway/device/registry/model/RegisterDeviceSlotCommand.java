package io.testforge.workergateway.device.registry.model;

import java.util.Set;

public record RegisterDeviceSlotCommand(
        String workerId,
        String deviceId,
        String platform,
        String deviceUri,
        Set<String> features,
        String resolution
) {
    public RegisterDeviceSlotCommand {
        features = features == null ? Set.of() : Set.copyOf(features);
    }
}
