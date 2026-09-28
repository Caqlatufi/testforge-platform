package io.testforge.runorchestrator.task.model;

import io.testforge.casecatalog.workflow.compile.model.DependencyCondition;

import java.util.UUID;

public record TaskDependencyView(
        UUID predecessorTaskId,
        UUID successorTaskId,
        DependencyCondition condition
) {
}
