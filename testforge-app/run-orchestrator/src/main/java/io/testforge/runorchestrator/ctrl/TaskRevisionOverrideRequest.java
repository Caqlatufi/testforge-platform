package io.testforge.runorchestrator.ctrl;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TaskRevisionOverrideRequest(
        @NotNull UUID workflowNodeId,
        @NotNull @Valid TargetRevisionRequest revision
) {
}
