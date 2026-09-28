package io.testforge.testjob.model;

import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.projectcatalog.model.EnvironmentPlatform;

import java.util.UUID;

public record TestJobCommand(
        UUID projectId,
        String code,
        String name,
        String description,
        UUID workflowId,
        int workflowVersion,
        RevisionType revisionType,
        String revisionValue,
        String pipelineExternalId,
        String environmentExternalId,
        EnvironmentPlatform platform,
        int priority,
        int processConcurrency,
        int deviceConcurrency
) {
    public TestJobCommand(UUID projectId, String code, String name, String description,
                          UUID workflowId, int workflowVersion, RevisionType revisionType,
                          String revisionValue, String pipelineExternalId, String environmentExternalId,
                          int priority, int processConcurrency, int deviceConcurrency) {
        this(projectId, code, name, description, workflowId, workflowVersion, revisionType,
                revisionValue, pipelineExternalId, environmentExternalId, EnvironmentPlatform.WINDOWS,
                priority, processConcurrency, deviceConcurrency);
    }
}
