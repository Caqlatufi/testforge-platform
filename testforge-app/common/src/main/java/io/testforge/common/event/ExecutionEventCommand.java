package io.testforge.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ExecutionEventCommand(
        UUID eventKey,
        UUID runId,
        UUID taskId,
        UUID attemptId,
        String type,
        Instant occurredAt,
        Map<String, Object> payload
) {
    public ExecutionEventCommand {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
