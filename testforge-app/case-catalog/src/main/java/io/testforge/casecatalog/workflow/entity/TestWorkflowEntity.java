package io.testforge.casecatalog.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "case_catalog_workflow",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_case_catalog_workflow_target_name",
                columnNames = {"project_id", "target_id", "name"}
        )
)
public class TestWorkflowEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "draft_graph", nullable = false, columnDefinition = "LONGTEXT")
    private String draftGraph;

    @Column(name = "draft_revision", nullable = false)
    private int draftRevision;

    @Column(name = "latest_version", nullable = false)
    private int latestVersion;

    @Version
    @Column(name = "persistence_version", nullable = false)
    private Long persistenceVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TestWorkflowEntity() {
    }

    public TestWorkflowEntity(
            UUID id,
            UUID projectId,
            UUID targetId,
            String name,
            String draftGraph,
            Instant createdAt
    ) {
        this.id = id;
        this.projectId = projectId;
        this.targetId = targetId;
        this.name = name;
        this.draftGraph = draftGraph;
        this.draftRevision = 0;
        this.latestVersion = 0;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void saveDraft(String graph, Instant changedAt) {
        this.draftGraph = graph;
        this.draftRevision++;
        this.updatedAt = changedAt;
    }

    public void markPublished(int version, Instant changedAt) {
        if (version != latestVersion + 1) {
            throw new IllegalArgumentException("发布版本必须连续递增");
        }
        this.latestVersion = version;
        this.updatedAt = changedAt;
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

    public String getName() {
        return name;
    }

    public String getDraftGraph() {
        return draftGraph;
    }

    public int getDraftRevision() {
        return draftRevision;
    }

    public int getLatestVersion() {
        return latestVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
