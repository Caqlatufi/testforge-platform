package io.testforge.dispatcher.port.outbound;

import java.util.Objects;
import java.util.UUID;

/**
 * Relay 交给消息适配器的稳定信封；具体 Redis Stream 路由由独立适配器实现。
 */
public record OutboxMessage(
        UUID eventId,
        String eventKey,
        UUID aggregateId,
        String aggregateType,
        String eventType,
        String payload,
        int deliveryAttempt
) {
    public OutboxMessage {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventKey, "eventKey must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }
}
