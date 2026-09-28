package io.testforge.runorchestrator.task.dag.release;

import io.testforge.casecatalog.workflow.compile.model.DependencyCondition;

import java.util.Objects;
import java.util.UUID;

public record DagDependency(
        UUID predecessorTaskId,
        UUID successorTaskId,
        DependencyCondition condition
) {

    public DagDependency {
        Objects.requireNonNull(predecessorTaskId, "predecessorTaskId must not be null");
        Objects.requireNonNull(successorTaskId, "successorTaskId must not be null");
        Objects.requireNonNull(condition, "condition must not be null");
        if (predecessorTaskId.equals(successorTaskId)) {
            throw new IllegalArgumentException("Task 不能依赖自身");
        }
    }
}
