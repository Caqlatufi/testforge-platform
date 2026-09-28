package io.testforge.casecatalog.workflow.ctrl;

import io.testforge.casecatalog.workflow.draft.WorkflowNodeType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Map;
import java.util.UUID;

public record WorkflowNodeRequest(
        @NotNull UUID id,
        @NotNull WorkflowNodeType type,
        @NotNull UUID referenceId,
        @NotNull @Positive Integer referenceVersion,
        @NotNull Boolean required,
        @Positive Integer timeoutSeconds,
        Map<String, Object> parameterOverrides,
        @NotNull Double positionX,
        @NotNull Double positionY
) {
}
