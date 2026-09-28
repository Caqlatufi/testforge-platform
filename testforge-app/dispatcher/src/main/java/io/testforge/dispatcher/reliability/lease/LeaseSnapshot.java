package io.testforge.dispatcher.reliability.lease;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 从 MySQL 读取的活动租约快照。版本只用于条件更新，不能作为内存锁替代品。
 */
public record LeaseSnapshot(
        UUID attemptId,
        UUID taskId,
        String workerId,
        UUID leaseToken,
        Instant leaseUntil,
        long version
) {

    public LeaseSnapshot {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }
}
