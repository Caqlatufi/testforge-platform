package io.testforge.runorchestrator.service.reliability;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** dispatcher 可使用的租约持久化快照，不暴露 run-orchestrator Repo。 */
public record AttemptLeaseRecord(
        UUID attemptId,
        UUID taskId,
        String workerId,
        UUID leaseToken,
        Instant leaseUntil,
        long version
) {
    public AttemptLeaseRecord {
        Objects.requireNonNull(attemptId, "attemptId 不能为空");
        Objects.requireNonNull(taskId, "taskId 不能为空");
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId 不能为空");
        }
        Objects.requireNonNull(leaseToken, "leaseToken 不能为空");
        Objects.requireNonNull(leaseUntil, "leaseUntil 不能为空");
        if (version < 0) {
            throw new IllegalArgumentException("version 不能小于 0");
        }
    }
}
