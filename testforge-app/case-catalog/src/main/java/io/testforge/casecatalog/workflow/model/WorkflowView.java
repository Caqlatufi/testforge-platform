package io.testforge.casecatalog.workflow.model;

import io.testforge.casecatalog.workflow.draft.WorkflowDraft;

import java.time.Instant;
import java.util.UUID;

public record WorkflowView(
        UUID id,
        UUID projectId,
        UUID targetId,
        String name,
        int draftRevision,
        int latestVersion,
        WorkflowDraft draft,
        Instant createdAt,
        Instant updatedAt
) {
}
