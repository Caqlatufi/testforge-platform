package io.testforge.workergateway.registry.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record WorkerNodeView(
        UUID instanceId,
        String workerId,
        String protocolVersion,
        Set<String> capabilities,
        int maxConcurrency,
        WorkerStatus status,
        Instant registeredAt,
        Instant lastHeartbeatAt,
        Instant expiresAt,
        Instant updatedAt,
        long version
) {
    public WorkerNodeView {
        capabilities = Set.copyOf(capabilities);
    }
}
