package io.testforge.testjob.model;

import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.projectcatalog.model.EnvironmentPlatform;

import java.time.Instant;
import java.util.UUID;

public record TestJobView(
        UUID id, UUID projectId, String code, String name, String description,
        UUID workflowId, int workflowVersion, String workflowChecksum,
        RevisionType revisionType, String revisionValue, String resolvedCommit,
        String commitMessage, Instant revisionResolvedAt,
        String pipelineExternalId, String pipelineName, String pipelineRevision,
        String environmentExternalId, String environmentName,
        EnvironmentPlatform platform,
        int priority, int processConcurrency, int deviceConcurrency,
        TestJobState state, long configVersion, long version,
        Instant createdAt, Instant updatedAt
) { }
