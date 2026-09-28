package io.testforge.runorchestrator.run.model;

import io.testforge.projectcatalog.revision.RevisionSelector;

import java.util.Map;
import java.util.UUID;

public record CreateRunCommand(
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
        RevisionSelector targetRevision,
        Map<UUID, RevisionSelector> taskRevisionOverrides,
        UUID deploymentProfileId,
        UUID testJobId,
        long jobConfigVersion,
        String testJobSnapshot,
        String deploymentEnvironment,
        String requestedPlatform
) {
    public CreateRunCommand(UUID projectId, UUID targetId, UUID environmentId, UUID workflowId,
                            int workflowVersion, int priority, int maxConcurrency,
                            int processConcurrency, int deviceConcurrency, UUID requestKey,
                            RevisionSelector targetRevision, Map<UUID, RevisionSelector> taskRevisionOverrides,
                            UUID deploymentProfileId, UUID testJobId, long jobConfigVersion,
                            String testJobSnapshot, String deploymentEnvironment) {
        this(projectId, targetId, environmentId, workflowId, workflowVersion, priority, maxConcurrency,
                processConcurrency, deviceConcurrency, requestKey, targetRevision, taskRevisionOverrides,
                deploymentProfileId, testJobId, jobConfigVersion, testJobSnapshot, deploymentEnvironment, null);
    }
    public CreateRunCommand(
            UUID projectId,
            UUID targetId,
            UUID environmentId,
            UUID workflowId,
            int workflowVersion,
            int priority,
            int maxConcurrency,
            UUID requestKey
    ) {
        this(
                projectId, targetId, environmentId, workflowId, workflowVersion,
                priority, maxConcurrency, maxConcurrency, maxConcurrency, requestKey, null, Map.of(), null,
                null, 0, null, null, null
        );
    }

    public CreateRunCommand(
            UUID projectId,
            UUID targetId,
            UUID environmentId,
            UUID workflowId,
            int workflowVersion,
            int priority,
            int maxConcurrency,
            UUID requestKey,
            RevisionSelector targetRevision,
            Map<UUID, RevisionSelector> taskRevisionOverrides
    ) {
        this(
                projectId, targetId, environmentId, workflowId, workflowVersion,
                priority, maxConcurrency, maxConcurrency, maxConcurrency, requestKey,
                targetRevision, taskRevisionOverrides, null, null, 0, null, null, null
        );
    }

    public CreateRunCommand(
            UUID projectId, UUID targetId, UUID environmentId, UUID workflowId, int workflowVersion,
            int priority, int maxConcurrency, int processConcurrency, int deviceConcurrency,
            UUID requestKey, RevisionSelector targetRevision, Map<UUID, RevisionSelector> taskRevisionOverrides
    ) {
        this(projectId, targetId, environmentId, workflowId, workflowVersion, priority, maxConcurrency,
                processConcurrency, deviceConcurrency, requestKey, targetRevision, taskRevisionOverrides, null,
                null, 0, null, null, null);
    }

    public CreateRunCommand(
            UUID projectId, UUID targetId, UUID environmentId, UUID workflowId, int workflowVersion,
            int priority, int maxConcurrency, int processConcurrency, int deviceConcurrency,
            UUID requestKey, RevisionSelector targetRevision, Map<UUID, RevisionSelector> taskRevisionOverrides,
            UUID deploymentProfileId
    ) {
        this(projectId, targetId, environmentId, workflowId, workflowVersion, priority, maxConcurrency,
                processConcurrency, deviceConcurrency, requestKey, targetRevision, taskRevisionOverrides,
                deploymentProfileId, null, 0, null, null, null);
    }

    public CreateRunCommand {
        taskRevisionOverrides = taskRevisionOverrides == null ? Map.of() : Map.copyOf(taskRevisionOverrides);
        if (processConcurrency < 1 || processConcurrency > 100) {
            throw new IllegalArgumentException("processConcurrency 必须在 1 到 100 之间");
        }
        if (deviceConcurrency < 1 || deviceConcurrency > 100) {
            throw new IllegalArgumentException("deviceConcurrency 必须在 1 到 100 之间");
        }
    }
}
