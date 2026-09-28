package io.testforge.projectcatalog.entity;

import io.testforge.projectcatalog.model.ProjectState;
import io.testforge.projectcatalog.model.TargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_catalog_project")
public class TestProjectEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "code", nullable = false, unique = true, length = 63)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private ProjectState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 32)
    private TargetType targetType;

    @Column(name = "repository_url", length = 2048)
    private String repositoryUrl;

    @Column(name = "default_branch", length = 255)
    private String defaultBranch;

    @Column(name = "ci_provider", length = 32)
    private String ciProvider;

    @Column(name = "ci_server_url", length = 2048)
    private String ciServerUrl;

    @Column(name = "ci_folder", length = 512)
    private String ciFolder;

    @Column(name = "ci_credential_ref", length = 255)
    private String ciCredentialRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TestProjectEntity() {
    }

    public TestProjectEntity(UUID id, String name, String code, ProjectState state, Instant createdAt) {
        this(id, name, code, state, null, null, null, null, null, null, null, createdAt);
    }

    public TestProjectEntity(UUID id, String name, String code, ProjectState state,
                             TargetType targetType, String repositoryUrl, String defaultBranch,
                             String ciProvider, String ciServerUrl, String ciFolder,
                             String ciCredentialRef, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.code = code;
        this.state = state;
        this.targetType = targetType;
        this.repositoryUrl = repositoryUrl;
        this.defaultBranch = defaultBranch;
        this.ciProvider = ciProvider;
        this.ciServerUrl = ciServerUrl;
        this.ciFolder = ciFolder;
        this.ciCredentialRef = ciCredentialRef;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void update(String name, String code, Instant changedAt) {
        update(name, code, targetType, repositoryUrl, defaultBranch, ciProvider, ciServerUrl, ciFolder,
                ciCredentialRef, changedAt);
    }

    public void update(String name, String code, TargetType targetType, String repositoryUrl,
                       String defaultBranch, String ciProvider, String ciServerUrl, String ciFolder,
                       String ciCredentialRef, Instant changedAt) {
        this.name = name;
        this.code = code;
        this.targetType = targetType;
        this.repositoryUrl = repositoryUrl;
        this.defaultBranch = defaultBranch;
        this.ciProvider = ciProvider;
        this.ciServerUrl = ciServerUrl;
        this.ciFolder = ciFolder;
        this.ciCredentialRef = ciCredentialRef;
        this.updatedAt = changedAt;
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public ProjectState getState() {
        return state;
    }

    public TargetType getTargetType() { return targetType; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public String getDefaultBranch() { return defaultBranch; }
    public String getCiProvider() { return ciProvider; }
    public String getCiServerUrl() { return ciServerUrl; }
    public String getCiFolder() { return ciFolder; }
    public String getCiCredentialRef() { return ciCredentialRef; }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
