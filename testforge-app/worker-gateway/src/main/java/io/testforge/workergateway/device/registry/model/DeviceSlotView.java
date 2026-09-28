package io.testforge.workergateway.device.registry.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record DeviceSlotView(
        UUID slotId,
        String workerId,
        String deviceId,
        String platform,
        String deviceUri,
        Set<String> features,
        String resolution,
        DeviceSlotStatus status,
        Instant registeredAt,
        Instant lastHeartbeatAt,
        Instant expiresAt,
        Instant updatedAt,
        long version
) {
    public DeviceSlotView {
        features = Set.copyOf(features);
    }
}
