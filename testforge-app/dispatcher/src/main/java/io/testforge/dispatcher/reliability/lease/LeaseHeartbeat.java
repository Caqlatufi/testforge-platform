package io.testforge.dispatcher.reliability.lease;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 一次由服务端时钟确认的心跳结果。 */
public record LeaseHeartbeat(
        UUID attemptId,
        Instant acceptedAt,
        Instant nextHeartbeatAt,
        Instant leaseUntil,
        long version
) {

    public LeaseHeartbeat {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        Objects.requireNonNull(nextHeartbeatAt, "nextHeartbeatAt must not be null");
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("heartbeat version must be positive");
        }
    }
}
