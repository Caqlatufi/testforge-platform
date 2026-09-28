package io.testforge.casecatalog.workflow.compile.model;

import java.time.Instant;
import java.util.UUID;

public record PublishedWorkflowVersionView(
        UUID id,
        UUID workflowId,
        UUID projectId,
        UUID targetId,
        int version,
        String checksum,
        CompiledWorkflowSnapshot compiledSnapshot,
        WorkflowDisplaySnapshot displaySnapshot,
        Instant publishedAt
) {
    public PublishedWorkflowVersionView(UUID id, UUID workflowId, UUID projectId, UUID targetId,
                                        int version, String checksum,
                                        CompiledWorkflowSnapshot compiledSnapshot, Instant publishedAt) {
        this(id, workflowId, projectId, targetId, version, checksum, compiledSnapshot, null, publishedAt);
    }
}
