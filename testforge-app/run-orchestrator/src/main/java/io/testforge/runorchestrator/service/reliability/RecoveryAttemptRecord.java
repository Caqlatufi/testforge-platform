package io.testforge.runorchestrator.service.reliability;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Reaper 用于补偿 LOST 与检测硬超时的最小 Attempt 信息。 */
public record RecoveryAttemptRecord(
        UUID taskId,
        UUID attemptId,
        int attemptNo,
        Instant occurredAt
) {
    public RecoveryAttemptRecord {
        Objects.requireNonNull(taskId, "taskId 不能为空");
        Objects.requireNonNull(attemptId, "attemptId 不能为空");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 必须大于 0");
        }
    }
}
