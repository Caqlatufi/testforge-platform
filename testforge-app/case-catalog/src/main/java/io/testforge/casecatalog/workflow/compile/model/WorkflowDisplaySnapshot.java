package io.testforge.casecatalog.workflow.compile.model;

import io.testforge.casecatalog.workflow.draft.WorkflowDraft;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WorkflowDisplaySnapshot(
        WorkflowDraft graph,
        Map<UUID, List<UUID>> nodeMapping
) {
    public WorkflowDisplaySnapshot {
        nodeMapping = nodeMapping == null ? Map.of() : Map.copyOf(nodeMapping);
    }
}
