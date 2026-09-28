package io.testforge.casecatalog.workflow.ctrl;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public record SaveWorkflowGraphRequest(
        @NotNull @PositiveOrZero Integer expectedVersion,
        @NotEmpty List<@Valid WorkflowNodeRequest> nodes,
        @NotNull List<@Valid WorkflowEdgeRequest> edges
) {
}
