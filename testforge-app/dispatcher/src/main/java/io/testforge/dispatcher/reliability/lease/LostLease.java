package io.testforge.dispatcher.reliability.lease;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Reaper 成功把 Attempt 条件更新为 LOST 后输出的事实。
 * TFP-011 汇总层可以消费该事实决定是否重新排队；本包不包含重试策略。
 */
public record LostLease(
        UUID attemptId,
        UUID taskId,
        String workerId,
        Instant expiredAt,
        Instant detectedAt
) {

    public LostLease {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        Objects.requireNonNull(expiredAt, "expiredAt must not be null");
        Objects.requireNonNull(detectedAt, "detectedAt must not be null");
    }
}
