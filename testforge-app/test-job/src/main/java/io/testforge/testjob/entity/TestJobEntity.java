package io.testforge.testjob.entity;

import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.projectcatalog.model.EnvironmentPlatform;
import io.testforge.testjob.model.TestJobCommand;
import io.testforge.testjob.model.TestJobState;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "test_job", uniqueConstraints = @UniqueConstraint(
        name = "uk_test_job_project_code", columnNames = {"project_id", "code"}))
public class TestJobEntity {
    @Id @Column(nullable = false, updatable = false) private UUID id;
    @Column(name = "project_id", nullable = false, updatable = false) private UUID projectId;
    @Column(nullable = false, length = 63) private String code;
    @Column(nullable = false, length = 200) private String name;
    @Column(length = 2000) private String description;
    @Column(name = "workflow_id", nullable = false) private UUID workflowId;
    @Column(name = "workflow_version", nullable = false) private int workflowVersion;
    @Column(name = "workflow_checksum", nullable = false, length = 71) private String workflowChecksum;
    @Enumerated(EnumType.STRING) @Column(name = "revision_type", nullable = false, length = 32)
    private RevisionType revisionType;
    @Column(name = "revision_value", length = 255) private String revisionValue;
    @Column(name = "resolved_commit", length = 64) private String resolvedCommit;
    @Column(name = "commit_message", length = 500) private String commitMessage;
    @Column(name = "revision_resolved_at") private Instant revisionResolvedAt;
    @Column(name = "pipeline_external_id", nullable = false, length = 255) private String pipelineExternalId;
    @Column(name = "pipeline_name", length = 200) private String pipelineName;
    @Column(name = "pipeline_revision", length = 71) private String pipelineRevision;
    @Column(name = "environment_external_id", length = 255) private String environmentExternalId;
    @Column(name = "environment_name", length = 200) private String environmentName;
    @Enumerated(EnumType.STRING) @Column(name = "requested_platform", nullable = false, length = 32,
            columnDefinition = "varchar(32) default 'WINDOWS'")
    private EnvironmentPlatform platform = EnvironmentPlatform.WINDOWS;
    @Column(name = "priority_no", nullable = false) private int priority;
    @Column(name = "process_concurrency", nullable = false) private int processConcurrency;
    @Column(name = "device_concurrency", nullable = false) private int deviceConcurrency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private TestJobState state;
    @Column(name = "config_version", nullable = false) private long configVersion;
    @Version @Column(name = "persistence_version", nullable = false) private long persistenceVersion;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected TestJobEntity() { }

    public TestJobEntity(UUID id, TestJobCommand command, String workflowChecksum,
                         String resolvedCommit, String commitMessage, Instant revisionResolvedAt, Instant now) {
        this.id = id; this.projectId = command.projectId(); this.code = command.code();
        this.name = command.name(); this.description = command.description();
        this.workflowId = command.workflowId(); this.workflowVersion = command.workflowVersion();
        this.workflowChecksum = workflowChecksum; this.revisionType = command.revisionType();
        this.revisionValue = command.revisionValue(); this.resolvedCommit = resolvedCommit;
        this.commitMessage = commitMessage; this.revisionResolvedAt = revisionResolvedAt;
        this.pipelineExternalId = command.pipelineExternalId();
        this.environmentExternalId = null; this.platform = command.platform(); this.priority = command.priority();
        this.processConcurrency = command.processConcurrency(); this.deviceConcurrency = command.deviceConcurrency();
        this.state = TestJobState.DRAFT; this.configVersion = 1; this.createdAt = now; this.updatedAt = now;
    }

    public void update(TestJobCommand command, String checksum, String resolvedCommit,
                       String commitMessage, Instant revisionResolvedAt, Instant now) {
        this.code = command.code(); this.name = command.name(); this.description = command.description();
        this.workflowId = command.workflowId(); this.workflowVersion = command.workflowVersion();
        this.workflowChecksum = checksum; this.revisionType = command.revisionType();
        this.revisionValue = command.revisionValue(); this.resolvedCommit = resolvedCommit;
        this.commitMessage = commitMessage; this.revisionResolvedAt = revisionResolvedAt;
        this.pipelineExternalId = command.pipelineExternalId();
        this.environmentExternalId = null; this.platform = command.platform(); this.priority = command.priority();
        this.processConcurrency = command.processConcurrency(); this.deviceConcurrency = command.deviceConcurrency();
        this.state = TestJobState.DRAFT; this.configVersion++; this.updatedAt = now;
    }

    public void activate(String pipelineName, String pipelineRevision, String environmentName, Instant now) {
        this.pipelineName = pipelineName; this.pipelineRevision = pipelineRevision;
        this.environmentName = environmentName; this.state = TestJobState.ACTIVE; this.updatedAt = now;
    }
    public void disable(Instant now) { this.state = TestJobState.DISABLED; this.updatedAt = now; }

    public void pinRevision(String resolvedCommit, String commitMessage, Instant revisionResolvedAt, Instant now) {
        this.resolvedCommit = resolvedCommit; this.commitMessage = commitMessage;
        this.revisionResolvedAt = revisionResolvedAt; this.updatedAt = now;
    }

    public UUID getId(){return id;} public UUID getProjectId(){return projectId;} public String getCode(){return code;}
    public String getName(){return name;} public String getDescription(){return description;}
    public UUID getWorkflowId(){return workflowId;} public int getWorkflowVersion(){return workflowVersion;}
    public String getWorkflowChecksum(){return workflowChecksum;} public RevisionType getRevisionType(){return revisionType;}
    public String getRevisionValue(){return revisionValue;} public String getResolvedCommit(){return resolvedCommit;}
    public String getCommitMessage(){return commitMessage;} public Instant getRevisionResolvedAt(){return revisionResolvedAt;}
    public String getPipelineExternalId(){return pipelineExternalId;}
    public String getPipelineName(){return pipelineName;} public String getPipelineRevision(){return pipelineRevision;}
    public String getEnvironmentExternalId(){return environmentExternalId;} public String getEnvironmentName(){return environmentName;}
    public EnvironmentPlatform getPlatform(){return platform == null ? EnvironmentPlatform.WINDOWS : platform;}
    public int getPriority(){return priority;} public int getProcessConcurrency(){return processConcurrency;}
    public int getDeviceConcurrency(){return deviceConcurrency;} public TestJobState getState(){return state;}
    public long getConfigVersion(){return configVersion;} public long getPersistenceVersion(){return persistenceVersion;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
