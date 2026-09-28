package io.testforge.workergateway.callback.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ArtifactPayload(
        @NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
        @NotNull UUID artifactId,
        @NotNull UUID attemptId,
        @NotBlank @Pattern(regexp = "LOG|JUNIT_XML|SCREENSHOT|VIDEO|AIRTEST_HTML|TRACE|WORLD_STATE|COMBAT_EVENT|OTHER") String type,
        @NotBlank @Size(max = 1024)
        @Pattern(regexp = "^(?!/)(?!.*(?:^|/)\\.\\.(?:/|$)).+$") String objectKey,
        @NotBlank @Size(max = 255)
        @Pattern(regexp = "^[^/\\s]+/[^/\\s]+$") String mediaType,
        @NotNull @PositiveOrZero Long sizeBytes,
        @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sha256,
        @NotNull Instant createdAt,
        @Size(max = 200) String stepName,
        Map<String, Object> metadata
) {
    public ArtifactPayload {
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }
}
