package io.testforge.runorchestrator.task.dag.release;

import io.testforge.runorchestrator.task.model.TaskState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次必须以 {@code taskId + expectedState + expectedVersion} 原子竞争的状态迁移。
 */
public record DagTaskTransition(
        UUID taskId,
        TaskState expectedState,
        long expectedVersion,
        TaskState targetState,
        UUID blockedByTaskId,
        String blockedReason,
        Instant occurredAt
) {

    public DagTaskTransition {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(expectedState, "expectedState must not be null");
        Objects.requireNonNull(targetState, "targetState must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion 不能小于 0");
        }
        if (expectedState != TaskState.CREATED && expectedState != TaskState.WAITING_DEPENDENCY) {
            throw new IllegalArgumentException("DAG 只能释放 CREATED 或 WAITING_DEPENDENCY Task");
        }
        if (targetState != TaskState.QUEUED && targetState != TaskState.BLOCKED) {
            throw new IllegalArgumentException("DAG 只能将 Task 推进为 QUEUED 或 BLOCKED");
        }
        if (targetState == TaskState.BLOCKED) {
            if (expectedState != TaskState.WAITING_DEPENDENCY) {
                throw new IllegalArgumentException("只有 WAITING_DEPENDENCY Task 可以被阻断");
            }
            Objects.requireNonNull(blockedByTaskId, "BLOCKED 必须提供 blockedByTaskId");
            if (blockedReason == null || blockedReason.isBlank()) {
                throw new IllegalArgumentException("BLOCKED 必须提供 blockedReason");
            }
        } else if (blockedByTaskId != null || blockedReason != null) {
            throw new IllegalArgumentException("QUEUED 迁移不能携带阻断信息");
        }
    }
}
