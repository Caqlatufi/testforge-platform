package io.testforge.runorchestrator.task.entity;

import io.testforge.casecatalog.workflow.compile.model.CompiledNode;
import io.testforge.casecatalog.workflow.compile.model.ExecutableNodeType;
import io.testforge.casecatalog.workflow.compile.model.PublishNodeType;
import io.testforge.casecatalog.testcase.model.InteractionMode;
import io.testforge.casecatalog.testcase.model.LeaseScope;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.model.TaskTargetRevision;
import io.testforge.runorchestrator.task.model.ResourceMode;
import io.testforge.runorchestrator.task.service.TaskStateMachine;
import io.testforge.projectcatalog.revision.RevisionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "run_orchestrator_task",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_run_orchestrator_task_node",
                        columnNames = {"run_id", "workflow_node_id"}
                ),
                @UniqueConstraint(
                        name = "uk_run_orchestrator_task_run_id",
                        columnNames = {"run_id", "id"}
                ),
                @UniqueConstraint(
                        name = "uk_run_orchestrator_task_sequence",
                        columnNames = {"run_id", "sequence_no"}
                )
        }
)
public class TestTaskEntity {

    private static final TaskStateMachine STATE_MACHINE = new TaskStateMachine();

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Column(name = "workflow_node_id", nullable = false, updatable = false)
    private UUID workflowNodeId;

    @Column(name = "source_path", nullable = false, updatable = false, length = 1000)
    private String sourcePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 16)
    private PublishNodeType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "executable_type", nullable = false, updatable = false, length = 16)
    private ExecutableNodeType executableType;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "script_version_id", nullable = false, updatable = false)
    private UUID scriptVersionId;

    @Column(name = "script_version", nullable = false, updatable = false)
    private int scriptVersion;

    @Column(name = "is_required", nullable = false, updatable = false)
    private boolean required;

    @Column(name = "runner", nullable = false, updatable = false, length = 64)
    private String runner;

    @Column(name = "platform", updatable = false, length = 64)
    private String platform;

    @Column(name = "required_features", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String requiredFeatures;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_mode", updatable = false, length = 32)
    private ResourceMode resourceMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "interaction_mode", updatable = false, length = 16)
    private InteractionMode interactionMode;

    @Column(name = "resource_profile", updatable = false, length = 64)
    private String resourceProfile;

    @Enumerated(EnumType.STRING)
    @Column(name = "lease_scope", updatable = false, length = 16)
    private LeaseScope leaseScope;

    @Column(name = "resource_session_key", updatable = false, length = 128)
    private String resourceSessionKey;

    @Column(name = "target_repository_url", updatable = false, length = 2048)
    private String targetRepositoryUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_revision_type", updatable = false, length = 32)
    private RevisionType targetRevisionType;

    @Column(name = "target_revision_value", updatable = false, length = 255)
    private String targetRevisionValue;

    @Column(name = "target_resolved_commit", updatable = false, length = 40)
    private String targetResolvedCommit;

    @Column(name = "target_revision_resolved_at", updatable = false)
    private Instant targetRevisionResolvedAt;

    @Column(name = "deployment_id", updatable = false)
    private UUID deploymentId;

    @Column(name = "source_ref", nullable = false, updatable = false, length = 1000)
    private String sourceRef;

    @Column(name = "script_checksum", nullable = false, updatable = false, length = 71)
    private String scriptChecksum;

    @Column(name = "timeout_seconds", nullable = false, updatable = false)
    private int timeoutSeconds;

    @Column(name = "parameters_json", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String parametersJson;

    @Column(name = "retry_policy_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String retryPolicyJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private TaskState state;

    @Column(name = "blocked_by_task_id")
    private UUID blockedByTaskId;

    @Column(name = "blocked_reason", length = 1000)
    private String blockedReason;

    @Column(name = "cancellation_requested_at")
    private Instant cancellationRequestedAt;

    @Column(name = "cancellation_reason", length = 1000)
    private String cancellationReason;

    @Column(name = "retry_available_at")
    private Instant retryAvailableAt;

    @Column(name = "scheduling_wait_reason", length = 64)
    private String schedulingWaitReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "persistence_version", nullable = false)
    private long persistenceVersion;

    protected TestTaskEntity() {
    }

    public TestTaskEntity(
            UUID id,
            UUID runId,
            int sequenceNo,
            CompiledNode node,
            String platform,
            String requiredFeatures,
            String parametersJson,
            String retryPolicyJson,
            TaskState initialState,
            Instant createdAt
    ) {
        this(
                id, runId, sequenceNo, node, platform, requiredFeatures, parametersJson,
                retryPolicyJson, null, initialState, createdAt
        );
    }

    public TestTaskEntity(
            UUID id,
            UUID runId,
            int sequenceNo,
            CompiledNode node,
            String platform,
            String requiredFeatures,
            String parametersJson,
            String retryPolicyJson,
            TaskTargetRevision targetRevision,
            TaskState initialState,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        if (sequenceNo < 0) {
            throw new IllegalArgumentException("sequenceNo 不能小于 0");
        }
        this.sequenceNo = sequenceNo;
        this.workflowNodeId = node.id();
        this.sourcePath = node.sourcePath();
        this.sourceType = node.sourceType();
        this.executableType = node.type();
        this.caseId = node.caseId();
        this.scriptVersionId = node.scriptVersionId();
        this.scriptVersion = node.scriptVersion();
        this.required = node.required();
        this.runner = node.runner();
        this.interactionMode = node.executionRequirement().interaction();
        this.resourceProfile = node.executionRequirement().resourceProfile();
        this.leaseScope = node.executionRequirement().leaseScope();
        this.resourceSessionKey = node.executionRequirement().sessionKey();
        this.resourceMode = ResourceMode.fromInteraction(this.interactionMode, node.runner());
        this.platform = platform;
        this.requiredFeatures = Objects.requireNonNull(requiredFeatures, "requiredFeatures must not be null");
        if (targetRevision != null) {
            this.targetRepositoryUrl = targetRevision.repositoryUrl();
            this.targetRevisionType = targetRevision.requestedType();
            this.targetRevisionValue = targetRevision.requestedValue();
            this.targetResolvedCommit = targetRevision.resolvedCommit();
            this.targetRevisionResolvedAt = targetRevision.resolvedAt();
        }
        this.sourceRef = node.sourceRef();
        this.scriptChecksum = node.scriptChecksum();
        this.timeoutSeconds = node.timeoutSeconds();
        this.parametersJson = Objects.requireNonNull(parametersJson, "parametersJson must not be null");
        this.retryPolicyJson = Objects.requireNonNull(retryPolicyJson, "retryPolicyJson must not be null");
        this.state = Objects.requireNonNull(initialState, "initialState must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = createdAt;
    }

    public void transitionTo(TaskState targetState, Instant now) {
        Objects.requireNonNull(targetState, "targetState must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (state == targetState) {
            return;
        }
        STATE_MACHINE.requireTransition(state, targetState);
        this.state = targetState;
        if (targetState != TaskState.QUEUED) {
            this.retryAvailableAt = null;
            this.schedulingWaitReason = null;
        }
        this.updatedAt = now;
        this.completedAt = targetState.isTerminal() ? now : null;
    }

    public void requestCancellation(String reason, Instant now) {
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (state.isTerminal()) {
            return;
        }
        if (cancellationRequestedAt == null) {
            cancellationRequestedAt = now;
            cancellationReason = reason;
            updatedAt = now;
        }
        if (!state.hasStarted()) {
            transitionTo(TaskState.CANCELLED, now);
        }
    }

    public void block(UUID predecessorTaskId, String reason, Instant now) {
        if (state != TaskState.WAITING_DEPENDENCY) {
            throw new IllegalStateException("只有等待依赖的 Task 可以被阻断: " + state);
        }
        this.blockedByTaskId = Objects.requireNonNull(predecessorTaskId,
                "predecessorTaskId must not be null");
        this.blockedReason = Objects.requireNonNull(reason, "reason must not be null");
        transitionTo(TaskState.BLOCKED, now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public UUID getWorkflowNodeId() {
        return workflowNodeId;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public PublishNodeType getSourceType() {
        return sourceType;
    }

    public ExecutableNodeType getExecutableType() {
        return executableType;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getScriptVersionId() {
        return scriptVersionId;
    }

    public int getScriptVersion() {
        return scriptVersion;
    }

    public boolean isRequired() {
        return required;
    }

    public String getRunner() {
        return runner;
    }

    public String getPlatform() {
        return platform;
    }

    public String getRequiredFeatures() {
        return requiredFeatures;
    }

    public void waitForDeployment(UUID deploymentId, Instant now) {
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId must not be null");
        transitionTo(TaskState.WAITING_DEPLOYMENT, now);
    }

    public void releaseDeployment(boolean hasDependencies, Instant now) {
        if (state != TaskState.WAITING_DEPLOYMENT) return;
        transitionTo(hasDependencies ? TaskState.WAITING_DEPENDENCY : TaskState.QUEUED, now);
    }

    public void blockByDeployment(String reason, Instant now) {
        if (state != TaskState.WAITING_DEPLOYMENT) return;
        this.blockedReason = Objects.requireNonNull(reason, "reason must not be null");
        transitionTo(TaskState.BLOCKED, now);
    }

    public ResourceMode getResourceMode() {
        return resourceMode == null ? ResourceMode.fromRunner(runner) : resourceMode;
    }

    public InteractionMode getInteractionMode() {
        return interactionMode == null
                ? (getResourceMode() == ResourceMode.EXCLUSIVE_DEVICE ? InteractionMode.UI : InteractionMode.HEADLESS)
                : interactionMode;
    }

    public String getResourceProfile() {
        return resourceProfile == null
                ? (getInteractionMode() == InteractionMode.UI ? "ui-default" : "script-small")
                : resourceProfile;
    }

    public LeaseScope getLeaseScope() {
        return leaseScope == null ? LeaseScope.CASE : leaseScope;
    }

    public String getResourceSessionKey() {
        return resourceSessionKey;
    }

    public void markSchedulingWait(String reason, Instant now) {
        if (state != TaskState.QUEUED) {
            throw new IllegalStateException("只有 QUEUED Task 可以记录资源等待原因");
        }
        this.schedulingWaitReason = Objects.requireNonNull(reason, "reason must not be null");
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public String getSchedulingWaitReason() {
        return schedulingWaitReason;
    }

    public TaskTargetRevision getTargetRevision() {
        if (targetRevisionType == null) {
            return null;
        }
        return new TaskTargetRevision(
                targetRepositoryUrl,
                targetRevisionType,
                targetRevisionValue,
                targetResolvedCommit,
                targetRevisionResolvedAt
        );
    }

    public UUID getDeploymentId() { return deploymentId; }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getScriptChecksum() {
        return scriptChecksum;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    public String getRetryPolicyJson() {
        return retryPolicyJson;
    }

    public TaskState getState() {
        return state;
    }

    public UUID getBlockedByTaskId() {
        return blockedByTaskId;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public Instant getCancellationRequestedAt() {
        return cancellationRequestedAt;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public Instant getRetryAvailableAt() {
        return retryAvailableAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getPersistenceVersion() {
        return persistenceVersion;
    }
}
