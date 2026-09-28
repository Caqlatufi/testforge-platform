package io.testforge.runorchestrator.comparison.model;

import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.runorchestrator.run.model.RunView;

import java.time.Instant;
import java.util.UUID;

public record ComparisonRunView(
        UUID id,
        UUID requestKey,
        UUID projectId,
        UUID targetId,
        UUID environmentId,
        UUID workflowId,
        int workflowVersion,
        RunView baselineRun,
        RunView candidateRun,
        RevisionType baselineRequestedType,
        String baselineRequestedValue,
        String baselineResolvedCommit,
        RevisionType candidateRequestedType,
        String candidateRequestedValue,
        String candidateResolvedCommit,
        Instant createdAt,
        UUID testJobId
) {
    public ComparisonRunView(UUID id, UUID requestKey, UUID projectId, UUID targetId, UUID environmentId,
                             UUID workflowId, int workflowVersion, RunView baselineRun, RunView candidateRun,
                             RevisionType baselineRequestedType, String baselineRequestedValue,
                             String baselineResolvedCommit, RevisionType candidateRequestedType,
                             String candidateRequestedValue, String candidateResolvedCommit, Instant createdAt) {
        this(id, requestKey, projectId, targetId, environmentId, workflowId, workflowVersion, baselineRun,
                candidateRun, baselineRequestedType, baselineRequestedValue, baselineResolvedCommit,
                candidateRequestedType, candidateRequestedValue, candidateResolvedCommit, createdAt, null);
    }
}
