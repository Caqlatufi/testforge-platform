package io.testforge.runorchestrator.comparison.model;

import io.testforge.projectcatalog.revision.RevisionSelector;

import java.util.Objects;
import java.util.UUID;

public record CreateComparisonRunCommand(
        UUID projectId,
        UUID targetId,
        UUID environmentId,
        UUID workflowId,
        int workflowVersion,
        int priority,
        int maxConcurrency,
        int processConcurrency,
        int deviceConcurrency,
        UUID requestKey,
        RevisionSelector baselineRevision,
        RevisionSelector candidateRevision,
        UUID deploymentProfileId,
        UUID testJobId,
        long jobConfigVersion,
        String baselineTestJobSnapshot,
        String candidateTestJobSnapshot,
        String deploymentEnvironment,
        String requestedPlatform
) {
    public CreateComparisonRunCommand(UUID projectId, UUID targetId, UUID environmentId, UUID workflowId,
                                      int workflowVersion, int priority, int maxConcurrency,
                                      int processConcurrency, int deviceConcurrency, UUID requestKey,
                                      RevisionSelector baselineRevision, RevisionSelector candidateRevision,
                                      UUID deploymentProfileId, UUID testJobId, long jobConfigVersion,
                                      String baselineTestJobSnapshot, String candidateTestJobSnapshot,
                                      String deploymentEnvironment) {
        this(projectId, targetId, environmentId, workflowId, workflowVersion, priority, maxConcurrency,
                processConcurrency, deviceConcurrency, requestKey, baselineRevision, candidateRevision,
                deploymentProfileId, testJobId, jobConfigVersion, baselineTestJobSnapshot,
                candidateTestJobSnapshot, deploymentEnvironment, null);
    }
    public CreateComparisonRunCommand(UUID projectId, UUID targetId, UUID environmentId, UUID workflowId,
                                      int workflowVersion, int priority, int maxConcurrency,
                                      int processConcurrency, int deviceConcurrency, UUID requestKey,
                                      RevisionSelector baselineRevision, RevisionSelector candidateRevision,
                                      UUID deploymentProfileId) {
        this(projectId, targetId, environmentId, workflowId, workflowVersion, priority, maxConcurrency,
                processConcurrency, deviceConcurrency, requestKey, baselineRevision, candidateRevision,
                deploymentProfileId, null, 0, null, null, null, null);
    }

    public CreateComparisonRunCommand {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(requestKey, "requestKey must not be null");
        Objects.requireNonNull(baselineRevision, "baselineRevision must not be null");
        Objects.requireNonNull(candidateRevision, "candidateRevision must not be null");
        if (workflowVersion < 1 || maxConcurrency < 1 || processConcurrency < 1 || deviceConcurrency < 1) {
            throw new IllegalArgumentException("版本号和并发数必须大于 0");
        }
    }
}
