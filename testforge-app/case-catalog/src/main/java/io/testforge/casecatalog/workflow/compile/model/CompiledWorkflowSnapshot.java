package io.testforge.casecatalog.workflow.compile.model;

import java.util.List;
import java.util.UUID;

public record CompiledWorkflowSnapshot(
        int schemaVersion,
        UUID workflowId,
        int workflowVersion,
        UUID projectId,
        UUID targetId,
        List<CompiledNode> nodes,
        List<CompiledEdge> edges,
        List<UUID> topologicalOrder
) {

    public CompiledWorkflowSnapshot {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        topologicalOrder = List.copyOf(topologicalOrder);
    }
}
