package io.testforge.workergateway.callback.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AttemptStartRequest(
        @NotBlank @Size(max = 128)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]*$") String workerId,
        @NotNull UUID leaseToken
) {
}
