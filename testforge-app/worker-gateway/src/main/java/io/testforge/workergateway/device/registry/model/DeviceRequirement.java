package io.testforge.workergateway.device.registry.model;

import java.util.Set;

public record DeviceRequirement(
        String platform,
        Set<String> requiredFeatures
) {
    public DeviceRequirement {
        requiredFeatures = requiredFeatures == null ? Set.of() : Set.copyOf(requiredFeatures);
    }
}
