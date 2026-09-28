package io.testforge.dispatcher.port.outbound;

import java.time.Instant;

/**
 * Relay 可据此决定是否标记已发布，或保留 Outbox 等待重试。
 */
public record DispatchResult(
        DispatchOutcome outcome,
        String stream,
        String redisRecordId,
        Instant retryAt,
        String detail
) {
    public boolean published() {
        return outcome == DispatchOutcome.PUBLISHED;
    }

    public boolean retryable() {
        return outcome == DispatchOutcome.PAUSED || outcome == DispatchOutcome.REDIS_UNAVAILABLE;
    }

    public static DispatchResult published(String stream, String redisRecordId) {
        return new DispatchResult(DispatchOutcome.PUBLISHED, stream, redisRecordId, null, "published");
    }

    public static DispatchResult paused(String stream, Instant retryAt, String detail) {
        return new DispatchResult(DispatchOutcome.PAUSED, stream, null, retryAt, detail);
    }

    public static DispatchResult unavailable(String stream, Instant retryAt, String detail) {
        return new DispatchResult(DispatchOutcome.REDIS_UNAVAILABLE, stream, null, retryAt, detail);
    }
}
