package io.testforge.workergateway.callback.model;

import io.testforge.workergateway.callback.service.CallbackValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AttemptCallbackRequest(
        @NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
        @NotNull UUID callbackKey,
        @NotNull UUID attemptId,
        @NotNull UUID leaseToken,
        @NotBlank @Size(max = 128)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]*$") String workerId,
        @NotNull CallbackStatus status,
        @NotNull Instant completedAt,
        @NotNull @PositiveOrZero Long durationMs,
        @NotBlank @Size(max = 2000) String summary,
        @Valid FailurePayload failure,
        @NotNull @Valid @Size(max = 100) List<ArtifactPayload> artifacts,
        @Size(max = 100) Map<String, BigDecimal> metrics
) {
    public AttemptCallbackRequest {
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts 不能为空"));
        metrics = metrics == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metrics));
    }

    public void validateFor(UUID pathAttemptId) {
        if (!attemptId.equals(pathAttemptId)) {
            throw new CallbackValidationException("路径 attemptId 与回调载荷 attemptId 不一致");
        }
        if ((status == CallbackStatus.PASSED) == (failure != null)) {
            throw new CallbackValidationException(
                    status == CallbackStatus.PASSED
                            ? "PASSED 回调不能携带 failure"
                            : "非 PASSED 回调必须携带 failure"
            );
        }
        for (ArtifactPayload artifact : artifacts) {
            if (!attemptId.equals(artifact.attemptId())) {
                throw new CallbackValidationException("附件 attemptId 必须与回调 attemptId 一致");
            }
            if (!"1.0.0".equals(artifact.schemaVersion())) {
                throw new CallbackValidationException("附件 schemaVersion 必须为 1.0.0");
            }
        }
    }
}
