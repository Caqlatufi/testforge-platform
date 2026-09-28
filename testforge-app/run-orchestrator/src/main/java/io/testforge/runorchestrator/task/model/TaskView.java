package io.testforge.runorchestrator.task.model;

import io.testforge.casecatalog.workflow.compile.model.ExecutableNodeType;
import io.testforge.casecatalog.workflow.compile.model.PublishNodeType;
import io.testforge.casecatalog.testcase.model.InteractionMode;
import io.testforge.casecatalog.testcase.model.LeaseScope;
import io.testforge.runorchestrator.model.attempt.AttemptView;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TaskView(
        UUID id,
        UUID runId,
        int sequenceNo,
        UUID workflowNodeId,
        String sourcePath,
        PublishNodeType sourceType,
        ExecutableNodeType executableType,
        UUID caseId,
        UUID scriptVersionId,
        int scriptVersion,
        boolean required,
        String runner,
        String platform,
        List<String> requiredFeatures,
        ResourceMode resourceMode,
        InteractionMode interactionMode,
        String resourceProfile,
        LeaseScope leaseScope,
        String resourceSessionKey,
        String schedulingWaitReason,
        TaskTargetRevision targetRevision,
        UUID deploymentId,
        String sourceRef,
        String scriptChecksum,
        int timeoutSeconds,
        Map<String, Object> parameters,
        TaskState state,
        long version,
        UUID blockedByTaskId,
        String blockedReason,
        Instant cancellationRequestedAt,
        String cancellationReason,
        Instant retryAvailableAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        List<TaskDependencyView> dependencies,
        List<AttemptView> attempts
) {
    public TaskView(
            UUID id, UUID runId, int sequenceNo, UUID workflowNodeId, String sourcePath,
            PublishNodeType sourceType, ExecutableNodeType executableType, UUID caseId,
            UUID scriptVersionId, int scriptVersion, boolean required, String runner, String platform,
            List<String> requiredFeatures, ResourceMode resourceMode, String schedulingWaitReason,
            TaskTargetRevision targetRevision, String sourceRef, String scriptChecksum, int timeoutSeconds,
            Map<String, Object> parameters, TaskState state, long version, UUID blockedByTaskId,
            String blockedReason, Instant cancellationRequestedAt, String cancellationReason,
            Instant retryAvailableAt, Instant createdAt, Instant updatedAt, Instant completedAt,
            List<TaskDependencyView> dependencies, List<AttemptView> attempts
    ) {
        this(id, runId, sequenceNo, workflowNodeId, sourcePath, sourceType, executableType, caseId,
                scriptVersionId, scriptVersion, required, runner, platform, requiredFeatures, resourceMode,
                resourceMode == ResourceMode.EXCLUSIVE_DEVICE ? InteractionMode.UI : InteractionMode.HEADLESS,
                resourceMode == ResourceMode.EXCLUSIVE_DEVICE ? "ui-default" : "script-small",
                LeaseScope.CASE, null, schedulingWaitReason, targetRevision, null, sourceRef, scriptChecksum, timeoutSeconds,
                parameters, state, version, blockedByTaskId, blockedReason, cancellationRequestedAt,
                cancellationReason, retryAvailableAt, createdAt, updatedAt, completedAt, dependencies, attempts);
    }
    public TaskView {
        requiredFeatures = List.copyOf(requiredFeatures);
        parameters = Map.copyOf(parameters);
        dependencies = List.copyOf(dependencies);
        attempts = List.copyOf(attempts);
    }
}
