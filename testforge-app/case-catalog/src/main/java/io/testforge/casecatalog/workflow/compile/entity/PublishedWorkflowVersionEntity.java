package io.testforge.casecatalog.workflow.compile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(
        name = "case_catalog_workflow_version",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_case_catalog_workflow_version",
                        columnNames = {"workflow_id", "version_no"}
                ),
                @UniqueConstraint(
                        name = "uk_case_catalog_workflow_publish_request",
                        columnNames = {"workflow_id", "request_key"}
                )
        }
)
public class PublishedWorkflowVersionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int version;

    @Column(name = "state", nullable = false, updatable = false, length = 16)
    private String state;

    @Column(name = "checksum", nullable = false, updatable = false, length = 71)
    private String checksum;

    @Column(name = "compiled_snapshot", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String compiledSnapshot;

    @Column(name = "display_snapshot", updatable = false, columnDefinition = "LONGTEXT")
    private String displaySnapshot;

    @Column(name = "request_key", updatable = false)
    private UUID requestKey;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    protected PublishedWorkflowVersionEntity() {
    }

    public PublishedWorkflowVersionEntity(
            UUID id,
            UUID workflowId,
            UUID projectId,
            UUID targetId,
            int version,
            String checksum,
            String compiledSnapshot,
            Instant publishedAt
    ) {
        this(id, workflowId, projectId, targetId, version, checksum, compiledSnapshot, null, publishedAt, null);
    }

    public PublishedWorkflowVersionEntity(
            UUID id,
            UUID workflowId,
            UUID projectId,
            UUID targetId,
            int version,
            String checksum,
            String compiledSnapshot,
            Instant publishedAt,
            UUID requestKey
    ) {
        this(id, workflowId, projectId, targetId, version, checksum, compiledSnapshot, null, publishedAt, requestKey);
    }

    public PublishedWorkflowVersionEntity(
            UUID id, UUID workflowId, UUID projectId, UUID targetId, int version,
            String checksum, String compiledSnapshot, String displaySnapshot,
            Instant publishedAt, UUID requestKey
    ) {
        this.id = id;
        this.workflowId = workflowId;
        this.projectId = projectId;
        this.targetId = targetId;
        this.version = version;
        this.state = "PUBLISHED";
        this.checksum = checksum;
        this.compiledSnapshot = compiledSnapshot;
        this.displaySnapshot = displaySnapshot;
        this.publishedAt = publishedAt;
        this.requestKey = requestKey;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public int getVersion() {
        return version;
    }

    public String getState() {
        return state;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getCompiledSnapshot() {
        return compiledSnapshot;
    }

    public String getDisplaySnapshot() { return displaySnapshot; }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public UUID getRequestKey() {
        return requestKey;
    }
}
