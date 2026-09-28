package io.testforge.casecatalog.workflow.compile.model;

import java.util.List;

public record WorkflowGraphInput(
        List<WorkflowNodeInput> nodes,
        List<WorkflowEdgeInput> edges
) {

    public WorkflowGraphInput {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
