package io.testforge.testjob.model;

import io.testforge.projectcatalog.revision.RevisionType;

import java.time.Instant;
import java.util.UUID;

public record TestJobSnapshot(
        UUID testJobId, String testJobName, long configVersion,
        UUID projectId, String projectName, String repositoryUrl,
        UUID workflowId, int workflowVersion, String workflowChecksum,
        RevisionType requestedRevisionType, String requestedRevisionValue, String resolvedCommit,
        UUID pipelineId, String pipelineProvider, String pipelineName, String pipelineRevision,
        String environmentExternalId, String environmentName, String requestedPlatform,
        String environmentResourcePoolKey,
        int priority, int processConcurrency, int deviceConcurrency,
        Instant capturedAt
) { }
