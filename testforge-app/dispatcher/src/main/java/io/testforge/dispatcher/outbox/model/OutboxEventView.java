package io.testforge.dispatcher.outbox.model;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventView(
        UUID id,
        String eventKey,
        UUID aggregateId,
        String aggregateType,
        String eventType,
        String payload,
        String payloadHash,
        OutboxStatus status,
        int deliveryAttempts,
        Instant availableAt,
        String leaseOwner,
        UUID leaseToken,
        Instant leaseUntil,
        Instant publishedAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
}
