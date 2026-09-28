package io.testforge.runorchestrator.comparison.entity;

import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.runorchestrator.comparison.model.CreateComparisonRunCommand;
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
@Table(name = "run_orchestrator_comparison_group", uniqueConstraints =
        @UniqueConstraint(name = "uk_comparison_group_request", columnNames = "request_key"))
public class ComparisonRunEntity {
    @Id
    private UUID id;
    @Column(name = "request_key", nullable = false, updatable = false)
    private UUID requestKey;
    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 71)
    private String requestFingerprint;
    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;
    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;
    @Column(name = "environment_id", updatable = false)
    private UUID environmentId;
    @Column(name = "test_job_id", updatable = false)
    private UUID testJobId;
    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;
    @Column(name = "workflow_version", nullable = false, updatable = false)
    private int workflowVersion;
    @Column(name = "baseline_run_id", nullable = false, updatable = false, unique = true)
    private UUID baselineRunId;
    @Column(name = "candidate_run_id", nullable = false, updatable = false, unique = true)
    private UUID candidateRunId;
    @Enumerated(EnumType.STRING)
    @Column(name = "baseline_requested_type", nullable = false, updatable = false, length = 16)
    private RevisionType baselineRequestedType;
    @Column(name = "baseline_requested_value", updatable = false, length = 255)
    private String baselineRequestedValue;
    @Column(name = "baseline_resolved_commit", nullable = false, updatable = false, length = 64)
    private String baselineResolvedCommit;
    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_requested_type", nullable = false, updatable = false, length = 16)
    private RevisionType candidateRequestedType;
    @Column(name = "candidate_requested_value", updatable = false, length = 255)
    private String candidateRequestedValue;
    @Column(name = "candidate_resolved_commit", nullable = false, updatable = false, length = 64)
    private String candidateResolvedCommit;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Version
    @Column(name = "persistence_version", nullable = false)
    private long persistenceVersion;

    protected ComparisonRunEntity() {
    }

    public ComparisonRunEntity(UUID id, CreateComparisonRunCommand command, String requestFingerprint,
                               UUID baselineRunId, String baselineResolvedCommit,
                               UUID candidateRunId, String candidateResolvedCommit, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.requestKey = command.requestKey();
        this.requestFingerprint = Objects.requireNonNull(requestFingerprint);
        this.projectId = command.projectId();
        this.targetId = command.targetId();
        this.environmentId = command.environmentId();
        this.testJobId = command.testJobId();
        this.workflowId = command.workflowId();
        this.workflowVersion = command.workflowVersion();
        this.baselineRunId = Objects.requireNonNull(baselineRunId);
        this.candidateRunId = Objects.requireNonNull(candidateRunId);
        this.baselineRequestedType = command.baselineRevision().type();
        this.baselineRequestedValue = command.baselineRevision().value();
        this.baselineResolvedCommit = Objects.requireNonNull(baselineResolvedCommit);
        this.candidateRequestedType = command.candidateRevision().type();
        this.candidateRequestedValue = command.candidateRevision().value();
        this.candidateResolvedCommit = Objects.requireNonNull(candidateResolvedCommit);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public boolean matches(String fingerprint) { return requestFingerprint.equals(fingerprint); }
    public UUID getId() { return id; }
    public UUID getRequestKey() { return requestKey; }
    public UUID getProjectId() { return projectId; }
    public UUID getTargetId() { return targetId; }
    public UUID getEnvironmentId() { return environmentId; }
    public UUID getTestJobId() { return testJobId; }
    public UUID getWorkflowId() { return workflowId; }
    public int getWorkflowVersion() { return workflowVersion; }
    public UUID getBaselineRunId() { return baselineRunId; }
    public UUID getCandidateRunId() { return candidateRunId; }
    public RevisionType getBaselineRequestedType() { return baselineRequestedType; }
    public String getBaselineRequestedValue() { return baselineRequestedValue; }
    public String getBaselineResolvedCommit() { return baselineResolvedCommit; }
    public RevisionType getCandidateRequestedType() { return candidateRequestedType; }
    public String getCandidateRequestedValue() { return candidateRequestedValue; }
    public String getCandidateResolvedCommit() { return candidateResolvedCommit; }
    public Instant getCreatedAt() { return createdAt; }
}
