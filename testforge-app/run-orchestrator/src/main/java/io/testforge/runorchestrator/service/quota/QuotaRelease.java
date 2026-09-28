package io.testforge.runorchestrator.service.quota;

import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.task.model.TaskState;

import java.util.UUID;

/**
 * 释放配额后的 Task 与 Run 收敛结果。
 */
public record QuotaRelease(
        UUID runId,
        UUID taskId,
        Outcome outcome,
        TaskState taskState,
        RunState runState,
        int occupiedSlots,
        int maxConcurrency
) {

    public enum Outcome {
        RELEASED,
        ALREADY_RELEASED
    }
}
