package io.testforge.runorchestrator.model.attempt;

import java.time.Instant;
import java.util.UUID;

/**
 * Run 查询聚合中的 Attempt 时间线项。leaseToken 不对查询 API 暴露。
 */
public record AttemptView(
        UUID id,
        UUID taskId,
        int attemptNo,
        String workerId,
        Instant leaseUntil,
        AttemptState state,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
