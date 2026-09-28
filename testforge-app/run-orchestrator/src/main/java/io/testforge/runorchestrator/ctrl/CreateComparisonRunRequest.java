package io.testforge.runorchestrator.ctrl;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateComparisonRunRequest(
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
        @NotNull @Valid TargetRevisionRequest baselineRevision,
        @NotNull @Valid TargetRevisionRequest candidateRevision,
        UUID deploymentProfileId
) {
}
