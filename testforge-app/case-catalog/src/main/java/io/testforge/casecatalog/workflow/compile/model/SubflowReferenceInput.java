package io.testforge.casecatalog.workflow.compile.model;

import java.util.UUID;

public record SubflowReferenceInput(
        UUID workflowId,
        int version,
        UUID projectId,
        UUID targetId,
        WorkflowGraphInput graph
) {

    public ReferenceKey key() {
        return new ReferenceKey(workflowId, version);
    }
}
