package io.testforge.runorchestrator.ctrl;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;

public record CreateRunRequest(
        @NotNull UUID projectId,
        @NotNull UUID targetId,
        @NotNull UUID environmentId,
        @NotNull UUID workflowId,
        @Min(1) int workflowVersion,
        @Min(0) @Max(9) int priority,
        @Min(1) @Max(20) int maxConcurrency,
        @Min(1) @Max(100) Integer processConcurrency,
        @Min(1) @Max(100) Integer deviceConcurrency,
        @NotNull UUID requestKey,
        @Valid TargetRevisionRequest targetRevision,
        @Valid List<TaskRevisionOverrideRequest> taskRevisionOverrides,
        UUID deploymentProfileId
) {
    public CreateRunRequest(
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
                priority, maxConcurrency, null, null, requestKey, null, List.of(), null
        );
    }
}
