package io.testforge.projectcatalog.entity;

import io.testforge.projectcatalog.model.TargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "project_catalog_target",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_project_catalog_target_project_name",
                columnNames = {"project_id", "name"}
        )
)
public class TestTargetEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private TargetType type;

    @Column(name = "repository_url", length = 2048)
    private String repositoryUrl;

    @Column(name = "default_branch", length = 255)
    private String defaultBranch;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TestTargetEntity() {
    }

    public TestTargetEntity(UUID id, UUID projectId, String name, TargetType type, Instant createdAt) {
        this(id, projectId, name, type, null, null, createdAt);
    }

    public TestTargetEntity(
            UUID id,
            UUID projectId,
            String name,
            TargetType type,
            String repositoryUrl,
            String defaultBranch,
            Instant createdAt
    ) {
        this.id = id;
        this.projectId = projectId;
        this.name = name;
        this.type = type;
        this.repositoryUrl = repositoryUrl;
        this.defaultBranch = defaultBranch;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void update(String name, TargetType type) {
        update(name, type, repositoryUrl, defaultBranch);
    }

    public void update(String name, TargetType type, String repositoryUrl, String defaultBranch) {
        this.name = name;
        this.type = type;
        this.repositoryUrl = repositoryUrl;
        this.defaultBranch = defaultBranch;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public TargetType getType() {
        return type;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
