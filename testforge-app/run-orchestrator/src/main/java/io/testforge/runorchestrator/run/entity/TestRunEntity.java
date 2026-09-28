package io.testforge.runorchestrator.run.entity;

import io.testforge.runorchestrator.run.model.CreateRunCommand;
import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.run.service.RunStateConflictException;
import io.testforge.runorchestrator.run.service.RunStateMachine;
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
        name = "run_orchestrator_run",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_run_orchestrator_run_request",
                columnNames = "request_key"
        )
)
public class TestRunEntity {

    private static final RunStateMachine STATE_MACHINE = new RunStateMachine();

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "environment_id", updatable = false)
    private UUID environmentId;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "workflow_version", nullable = false, updatable = false)
    private int workflowVersion;

    @Column(name = "workflow_checksum", nullable = false, updatable = false, length = 71)
    private String workflowChecksum;

    @Column(name = "workflow_snapshot", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String workflowSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private RunState state;

    @Column(name = "priority_no", nullable = false, updatable = false)
    private int priority;

    @Column(name = "max_concurrency", nullable = false, updatable = false)
    private int maxConcurrency;

    @Column(name = "process_concurrency", updatable = false)
    private Integer processConcurrency;

    @Column(name = "device_concurrency", updatable = false)
    private Integer deviceConcurrency;

    @Column(name = "request_key", nullable = false, updatable = false)
    private UUID requestKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 71)
    private String requestFingerprint;

    @Column(name = "comparison_group_id")
    private UUID comparisonGroupId;

    @Column(name = "test_job_id")
    private UUID testJobId;

    @Column(name = "job_config_version")
    private Long jobConfigVersion;

    @Column(name = "test_job_snapshot", columnDefinition = "LONGTEXT")
    private String testJobSnapshot;

    @Column(name = "cancellation_request_key")
    private UUID cancellationRequestKey;

    @Column(name = "cancellation_requested_at")
    private Instant cancellationRequestedAt;

    @Column(name = "cancellation_reason", length = 1000)
    private String cancellationReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "persistence_version", nullable = false)
    private long persistenceVersion;

    protected TestRunEntity() {
    }

    public TestRunEntity(
            UUID id,
            CreateRunCommand command,
            String workflowChecksum,
            String workflowSnapshot,
            String requestFingerprint,
            RunState state,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.projectId = command.projectId();
        this.targetId = command.targetId();
        this.environmentId = command.environmentId();
        this.workflowId = command.workflowId();
        this.workflowVersion = command.workflowVersion();
        this.workflowChecksum = Objects.requireNonNull(workflowChecksum, "workflowChecksum must not be null");
        this.workflowSnapshot = Objects.requireNonNull(workflowSnapshot, "workflowSnapshot must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.priority = command.priority();
        this.maxConcurrency = command.maxConcurrency();
        this.processConcurrency = command.processConcurrency();
        this.deviceConcurrency = command.deviceConcurrency();
        this.requestKey = command.requestKey();
        this.requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint must not be null");
        this.testJobId = command.testJobId();
        this.jobConfigVersion = command.testJobId() == null ? null : command.jobConfigVersion();
        this.testJobSnapshot = command.testJobSnapshot();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = createdAt;
    }

    public boolean matches(CreateRunCommand command, String fingerprint) {
        return projectId.equals(command.projectId())
                && targetId.equals(command.targetId())
                && Objects.equals(environmentId, command.environmentId())
                && workflowId.equals(command.workflowId())
                && workflowVersion == command.workflowVersion()
                && priority == command.priority()
                && maxConcurrency == command.maxConcurrency()
                && getProcessConcurrency() == command.processConcurrency()
                && getDeviceConcurrency() == command.deviceConcurrency()
                && requestFingerprint.equals(fingerprint);
    }

    public void requestCancellation(UUID cancellationRequestKey, String reason, Instant now) {
        if (state.isTerminal() && state != RunState.CANCELLED) {
            throw new RunStateConflictException("终态 Run 不能取消: " + state);
        }
        if (this.cancellationRequestKey != null) {
            if (!this.cancellationRequestKey.equals(cancellationRequestKey)
                    || !Objects.equals(this.cancellationReason, reason)) {
                throw new IllegalArgumentException("Run 已记录不同的取消请求");
            }
            return;
        }
        this.cancellationRequestKey = Objects.requireNonNull(cancellationRequestKey,
                "cancellationRequestKey must not be null");
        this.cancellationReason = Objects.requireNonNull(reason, "reason must not be null");
        this.cancellationRequestedAt = Objects.requireNonNull(now, "now must not be null");
        STATE_MACHINE.requireTransition(state, RunState.CANCELLING);
        this.state = RunState.CANCELLING;
        this.updatedAt = now;
    }

    public void applyAggregateState(RunState aggregateState, Instant now) {
        Objects.requireNonNull(aggregateState, "aggregateState must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (state.isTerminal() && state != aggregateState) {
            throw new RunStateConflictException("Run 终态不可改变: " + state + " -> " + aggregateState);
        }
        if (cancellationRequestedAt != null
                && aggregateState != RunState.CANCELLING
                && aggregateState != RunState.CANCELLED) {
            throw new RunStateConflictException("已请求取消的 Run 只能收敛为 CANCELLING 或 CANCELLED");
        }
        STATE_MACHINE.requireTransition(state, aggregateState);
        this.state = aggregateState;
        this.updatedAt = now;
        this.completedAt = aggregateState.isTerminal() ? now : null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public int getWorkflowVersion() {
        return workflowVersion;
    }

    public String getWorkflowChecksum() {
        return workflowChecksum;
    }

    public String getWorkflowSnapshot() {
        return workflowSnapshot;
    }

    public RunState getState() {
        return state;
    }

    public int getPriority() {
        return priority;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public int getProcessConcurrency() {
        return processConcurrency == null || processConcurrency < 1 ? maxConcurrency : processConcurrency;
    }

    public int getDeviceConcurrency() {
        return deviceConcurrency == null || deviceConcurrency < 1 ? maxConcurrency : deviceConcurrency;
    }

    public UUID getRequestKey() {
        return requestKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public UUID getComparisonGroupId() {
        return comparisonGroupId;
    }

    public UUID getTestJobId() { return testJobId; }
    public long getJobConfigVersion() { return jobConfigVersion == null ? 0 : jobConfigVersion; }
    public String getTestJobSnapshot() { return testJobSnapshot; }

    public void assignComparisonGroup(UUID comparisonGroupId) {
        Objects.requireNonNull(comparisonGroupId, "comparisonGroupId must not be null");
        if (this.comparisonGroupId != null && !this.comparisonGroupId.equals(comparisonGroupId)) {
            throw new IllegalStateException("Run 已属于其他 comparisonGroup: " + this.comparisonGroupId);
        }
        this.comparisonGroupId = comparisonGroupId;
    }

    public UUID getCancellationRequestKey() {
        return cancellationRequestKey;
    }

    public Instant getCancellationRequestedAt() {
        return cancellationRequestedAt;
    }

    public String getCancellationReason() {
        return cancellationReason;
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
