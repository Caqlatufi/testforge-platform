package io.testforge.projectcatalog.ctrl;

import io.testforge.projectcatalog.model.EnvironmentPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Map;

public record CreateEnvironmentRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2048) String endpoint,
        @NotBlank @Size(max = 255) String providerEnvironmentKey,
        @NotNull EnvironmentPlatform platform,
        @NotBlank @Size(max = 128) String resourcePoolKey,
        @Min(1) @Max(1) int capacity,
        boolean enabled,
        boolean initializeOnNextDeploy,
        @NotNull Map<String, Object> config,
        @NotNull Map<String, String> secretRefs
) {
}
