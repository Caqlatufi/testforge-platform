package io.testforge.casecatalog.workflow.ctrl;

import io.testforge.casecatalog.workflow.draft.WorkflowEdgeCondition;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record WorkflowEdgeRequest(
        @NotNull UUID predecessorNodeId,
        @NotNull UUID successorNodeId,
        @NotNull WorkflowEdgeCondition condition
) {
}
