package io.testforge.dispatcher.outbox.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OutboxClaim(
        UUID id,
        String eventKey,
        UUID aggregateId,
        String aggregateType,
        String eventType,
        String payload,
        int deliveryAttempt,
        String leaseOwner,
        UUID leaseToken,
        Instant leaseUntil
) {
    public OutboxClaim {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(eventKey, "eventKey must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(leaseOwner, "leaseOwner must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
    }
}
