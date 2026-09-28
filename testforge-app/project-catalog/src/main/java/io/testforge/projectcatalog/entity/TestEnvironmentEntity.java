package io.testforge.projectcatalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import io.testforge.projectcatalog.model.EnvironmentPlatform;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "project_catalog_environment",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_project_catalog_environment_target_name",
                        columnNames = {"target_id", "name"}),
                @UniqueConstraint(name = "uk_project_catalog_environment_target_provider_key",
                        columnNames = {"target_id", "provider_environment_key"}),
                @UniqueConstraint(name = "uk_project_catalog_environment_global_name",
                        columnNames = {"name"}),
                @UniqueConstraint(name = "uk_project_catalog_environment_global_provider_key",
                        columnNames = {"provider_environment_key"})
        }
)
public class TestEnvironmentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "endpoint", length = 2048)
    private String endpoint;

    @Column(name = "provider_environment_key", length = 255)
    private String providerEnvironmentKey;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "platform", length = 32)
    private EnvironmentPlatform platform;

    @Column(name = "resource_pool_key", length = 128)
    private String resourcePoolKey;

    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "initialize_on_next_deploy")
    private Boolean initializeOnNextDeploy;

    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "secret_refs_json", nullable = false, columnDefinition = "TEXT")
    private String secretRefsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TestEnvironmentEntity() {
    }

    public TestEnvironmentEntity(UUID id, UUID projectId, UUID targetId, String name, String endpoint,
                                 String providerEnvironmentKey, EnvironmentPlatform platform,
                                 String resourcePoolKey, int capacity, boolean enabled,
                                 boolean initializeOnNextDeploy,
                                 String configJson, String secretRefsJson, Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.targetId = targetId;
        this.name = name;
        this.endpoint = endpoint;
        this.providerEnvironmentKey = providerEnvironmentKey;
        this.platform = platform;
        this.resourcePoolKey = resourcePoolKey;
        this.capacity = capacity;
        this.enabled = enabled;
        this.initializeOnNextDeploy = initializeOnNextDeploy;
        this.configJson = configJson;
        this.secretRefsJson = secretRefsJson;
        this.createdAt = createdAt;
    }

    public void update(String name, String endpoint, String providerEnvironmentKey, EnvironmentPlatform platform,
                       String resourcePoolKey, int capacity, boolean enabled,
                       boolean initializeOnNextDeploy, String configJson, String secretRefsJson) {
        this.name = name;
        this.endpoint = endpoint;
        this.providerEnvironmentKey = providerEnvironmentKey;
        this.platform = platform;
        this.resourcePoolKey = resourcePoolKey;
        this.capacity = capacity;
        this.enabled = enabled;
        this.initializeOnNextDeploy = initializeOnNextDeploy;
        this.configJson = configJson;
        this.secretRefsJson = secretRefsJson;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getTargetId() { return targetId; }
    public String getName() { return name; }
    public String getEndpoint() { return endpoint; }
    public String getProviderEnvironmentKey() {
        return providerEnvironmentKey == null || providerEnvironmentKey.isBlank() ? name : providerEnvironmentKey;
    }
    public EnvironmentPlatform getPlatform() { return platform == null ? EnvironmentPlatform.WINDOWS : platform; }
    public String getResourcePoolKey() {
        return resourcePoolKey == null || resourcePoolKey.isBlank()
                ? getPlatform().name().toLowerCase() : resourcePoolKey;
    }
    public int getCapacity() { return capacity == null || capacity < 1 ? 1 : capacity; }
    public boolean isEnabled() { return enabled == null || enabled; }
    public boolean isInitializeOnNextDeploy() { return initializeOnNextDeploy != null && initializeOnNextDeploy; }
    public void markInitialized() { this.initializeOnNextDeploy = false; }
    public String getConfigJson() { return configJson; }
    public String getSecretRefsJson() { return secretRefsJson; }
    public Instant getCreatedAt() { return createdAt; }
}
