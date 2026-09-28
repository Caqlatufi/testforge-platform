package io.testforge.casecatalog.workflow.compile.model;

import java.util.UUID;

public record WorkflowEdgeInput(
        UUID predecessorNodeId,
        UUID successorNodeId,
        DependencyCondition condition
) {
}
