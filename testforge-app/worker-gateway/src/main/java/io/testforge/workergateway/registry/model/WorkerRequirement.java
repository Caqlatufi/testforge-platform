package io.testforge.workergateway.registry.model;

import java.util.Set;

public record WorkerRequirement(
        String protocolVersion,
        String runner,
        String platform,
        Set<String> requiredFeatures
) {
    public WorkerRequirement {
        requiredFeatures = requiredFeatures == null ? Set.of() : Set.copyOf(requiredFeatures);
    }
}
