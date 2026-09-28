package io.testforge.workergateway.artifact.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ArtifactUploadPolicyRequest(
        @NotBlank @Size(max = 128)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]*$") String workerId,
        @NotNull UUID leaseToken,
        @NotBlank @Size(max = 1024) String objectKey,
        @NotBlank @Size(max = 255) String mediaType,
        @PositiveOrZero long sizeBytes,
        @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sha256
) {
}
