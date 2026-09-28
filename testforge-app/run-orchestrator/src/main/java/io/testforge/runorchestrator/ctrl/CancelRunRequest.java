package io.testforge.runorchestrator.ctrl;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CancelRunRequest(
        @NotNull UUID requestKey,
        @NotBlank @Size(max = 500) String reason
) {
}
