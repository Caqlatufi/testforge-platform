package io.testforge.runorchestrator.task.model;

import io.testforge.projectcatalog.revision.RevisionType;

import java.time.Instant;

public record TaskTargetRevision(
        String repositoryUrl,
        RevisionType requestedType,
        String requestedValue,
        String resolvedCommit,
        Instant resolvedAt
) {
}
