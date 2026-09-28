package io.testforge.runorchestrator.task.dag.release;

import io.testforge.runorchestrator.task.model.TaskState;

import java.util.Objects;
import java.util.UUID;

/**
 * DAG 判定所需的最小 Task 快照。
 *
 * <p>{@code required} 只影响 Run 聚合结论，不改变依赖边是否满足。</p>
 */
public record DagTaskSnapshot(
        UUID taskId,
        TaskState state,
        boolean required,
        long version
) {

    public DagTaskSnapshot {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version 不能小于 0");
        }
    }
}
