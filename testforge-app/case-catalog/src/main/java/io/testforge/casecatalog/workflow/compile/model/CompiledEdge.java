package io.testforge.casecatalog.workflow.compile.model;

import java.util.UUID;

public record CompiledEdge(
        UUID predecessorNodeId,
        UUID successorNodeId,
        DependencyCondition condition
) {
}
