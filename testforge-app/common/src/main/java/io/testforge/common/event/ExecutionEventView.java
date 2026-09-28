package io.testforge.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ExecutionEventView(
        long id,
        UUID eventKey,
        UUID runId,
        UUID taskId,
        UUID attemptId,
        String type,
        Instant occurredAt,
        Map<String, Object> payload
) { }
