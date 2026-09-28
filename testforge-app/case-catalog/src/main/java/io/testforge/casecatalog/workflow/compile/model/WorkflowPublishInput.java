package io.testforge.casecatalog.workflow.compile.model;

import java.util.UUID;

public record WorkflowPublishInput(
        UUID workflowId,
        UUID projectId,
        UUID targetId,
        int version,
        WorkflowGraphInput graph,
        ReferenceCatalogInput references
) {
}
