package io.testforge.runorchestrator.service.quota;

import java.util.UUID;

/**
 * 一次 Run 配额占用尝试的确定性结果。
 */
public record QuotaReservation(
        UUID runId,
        UUID taskId,
        Outcome outcome,
        int occupiedSlots,
        int maxConcurrency
) {

    public boolean acquired() {
        return outcome == Outcome.ACQUIRED;
    }

    public enum Outcome {
        ACQUIRED,
        ALREADY_ACQUIRED,
        LIMIT_REACHED,
        RUN_NOT_SCHEDULABLE,
        RETRY_NOT_READY,
        TASK_NOT_QUEUED
    }
}
