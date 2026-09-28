package io.testforge.runorchestrator.service.scheduling;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 一个等待占用 Run 配额的持久化 Task 候选。 */
public record SchedulingCandidate(
        UUID taskId,
        UUID runId,
        int runPriority,
        int sequenceNo,
        Instant queuedAt
) {

    public SchedulingCandidate {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(queuedAt, "queuedAt must not be null");
        if (runPriority < 0 || runPriority > 9) {
            throw new IllegalArgumentException("runPriority 必须在 0 到 9 之间");
        }
        if (sequenceNo < 0) {
            throw new IllegalArgumentException("sequenceNo 不能小于 0");
        }
    }
}
