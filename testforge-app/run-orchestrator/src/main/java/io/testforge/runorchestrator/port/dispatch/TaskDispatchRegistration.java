package io.testforge.runorchestrator.port.dispatch;

import io.testforge.runorchestrator.task.model.ResourceMode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * run-orchestrator 向派发模块登记的不可变任务快照。
 */
public record TaskDispatchRegistration(
        UUID taskId,
        UUID runId,
        String runner,
        ResourceMode resourceMode,
        String platform,
        String sourceRef,
        String scriptChecksum,
        int timeoutSeconds,
        List<String> requiredFeatures,
        Map<String, Object> parameters,
        int priority,
        int attemptNo,
        Instant queuedAt
) {
    public TaskDispatchRegistration {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(runner, "runner must not be null");
        Objects.requireNonNull(resourceMode, "resourceMode must not be null");
        Objects.requireNonNull(sourceRef, "sourceRef must not be null");
        Objects.requireNonNull(scriptChecksum, "scriptChecksum must not be null");
        Objects.requireNonNull(requiredFeatures, "requiredFeatures must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
        Objects.requireNonNull(queuedAt, "queuedAt must not be null");
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds 必须大于 0");
        }
        if (priority < 0 || priority > 9) {
            throw new IllegalArgumentException("priority 必须在 0 到 9 之间");
        }
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 必须大于 0");
        }
        requiredFeatures = List.copyOf(requiredFeatures);
        parameters = Map.copyOf(parameters);
    }

    public TaskDispatchRegistration(
            UUID taskId, UUID runId, String runner, String platform, String sourceRef,
            String scriptChecksum, int timeoutSeconds, List<String> requiredFeatures,
            Map<String, Object> parameters, int priority, int attemptNo, Instant queuedAt
    ) {
        this(taskId, runId, runner, ResourceMode.fromRunner(runner), platform, sourceRef,
                scriptChecksum, timeoutSeconds, requiredFeatures, parameters, priority, attemptNo, queuedAt);
    }
}
