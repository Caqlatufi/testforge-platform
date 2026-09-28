package io.testforge.cicdgateway.deployment.entity;

import io.testforge.cicdgateway.deployment.model.CreateDeploymentProfileCommand;
import io.testforge.cicdgateway.deployment.model.DeploymentProviderType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cicd_deployment_profile", uniqueConstraints = @UniqueConstraint(
        name = "uk_cicd_profile_target_name", columnNames = {"target_id", "name"}))
public class DeploymentProfileEntity {
    @Id private UUID id;
    @Column(name = "target_id", nullable = false, updatable = false) private UUID targetId;
    @Column(nullable = false, length = 128) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private DeploymentProviderType provider;
    @Column(name = "server_url", nullable = false, length = 2048) private String serverUrl;
    @Column(name = "job_name", nullable = false, length = 512) private String jobName;
    @Column(name = "credential_ref", length = 128) private String credentialRef;
    @Column(name = "build_config_digest", nullable = false, length = 71) private String buildConfigDigest;
    @Column(name = "max_concurrency", nullable = false) private int maxConcurrency;
    @Column(name = "ttl_seconds", nullable = false) private int ttlSeconds;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected DeploymentProfileEntity() { }

    public DeploymentProfileEntity(UUID id, UUID targetId, CreateDeploymentProfileCommand command, Instant now) {
        this.id = id; this.targetId = targetId; this.name = command.name();
        this.provider = DeploymentProviderType.JENKINS; this.serverUrl = command.serverUrl();
        this.jobName = command.jobName(); this.credentialRef = command.credentialRef();
        this.buildConfigDigest = command.buildConfigDigest(); this.maxConcurrency = command.maxConcurrency();
        this.ttlSeconds = command.ttlSeconds(); this.enabled = true; this.createdAt = now;
    }

    public void synchronize(CreateDeploymentProfileCommand command) {
        this.name = command.name(); this.provider = DeploymentProviderType.JENKINS;
        this.serverUrl = command.serverUrl(); this.jobName = command.jobName();
        this.credentialRef = command.credentialRef(); this.buildConfigDigest = command.buildConfigDigest();
        this.maxConcurrency = command.maxConcurrency(); this.ttlSeconds = command.ttlSeconds();
        this.enabled = true;
    }

    public UUID getId() { return id; }
    public UUID getTargetId() { return targetId; }
    public String getName() { return name; }
    public DeploymentProviderType getProvider() { return provider; }
    public String getServerUrl() { return serverUrl; }
    public String getJobName() { return jobName; }
    public String getCredentialRef() { return credentialRef; }
    public String getBuildConfigDigest() { return buildConfigDigest; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public int getTtlSeconds() { return ttlSeconds; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
}
