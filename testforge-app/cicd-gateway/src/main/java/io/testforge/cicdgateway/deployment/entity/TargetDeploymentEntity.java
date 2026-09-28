package io.testforge.cicdgateway.deployment.entity;

import io.testforge.cicdgateway.deployment.model.DeploymentCallbackCommand;
import io.testforge.cicdgateway.deployment.model.DeploymentState;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cicd_target_deployment", uniqueConstraints = @UniqueConstraint(
        name = "uk_cicd_deployment_key", columnNames = "deployment_key"))
public class TargetDeploymentEntity {
    @Id private UUID id;
    @Column(name = "target_id", nullable = false, updatable = false) private UUID targetId;
    @Column(name = "profile_id", nullable = false, updatable = false) private UUID profileId;
    @Column(name = "environment_external_id", length = 255, updatable = false) private String environmentExternalId;
    @Column(name = "initialization_requested", updatable = false) private Boolean initializationRequested;
    @Column(name = "resolved_commit", nullable = false, updatable = false, length = 40) private String resolvedCommit;
    @Column(name = "deployment_key", nullable = false, updatable = false, length = 71) private String deploymentKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private DeploymentState state;
    @Column(name = "provider_run_id", length = 2048) private String providerRunId;
    @Column(length = 2048) private String endpoint;
    @Column(name = "artifact_uri", length = 2048) private String artifactUri;
    @Column(name = "artifact_digest", length = 255) private String artifactDigest;
    @Column(length = 1000) private String summary;
    @Column(name = "last_callback_key") private UUID lastCallbackKey;
    @Column(name = "last_callback_hash", length = 71) private String lastCallbackHash;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "expires_at") private Instant expiresAt;
    @Version private long version;

    protected TargetDeploymentEntity() { }

    public TargetDeploymentEntity(UUID id, UUID targetId, UUID profileId, String commit, String key, Instant now) {
        this(id, targetId, profileId, commit, key, null, false, now);
    }

    public TargetDeploymentEntity(UUID id, UUID targetId, UUID profileId, String commit, String key,
                                  String environmentExternalId, Instant now) {
        this(id, targetId, profileId, commit, key, environmentExternalId, false, now);
    }

    public TargetDeploymentEntity(UUID id, UUID targetId, UUID profileId, String commit, String key,
                                  String environmentExternalId, boolean initializationRequested, Instant now) {
        this.id = id; this.targetId = targetId; this.profileId = profileId; this.resolvedCommit = commit;
        this.deploymentKey = key; this.environmentExternalId = environmentExternalId;
        this.initializationRequested = initializationRequested;
        this.state = DeploymentState.PENDING; this.createdAt = now; this.updatedAt = now;
    }

    public void triggered(String runId, Instant now) {
        if (state != DeploymentState.PENDING) return;
        providerRunId = runId; state = DeploymentState.TRIGGERED; updatedAt = now;
    }

    public void triggerFailed(String message, Instant now) {
        if (state != DeploymentState.PENDING) return;
        state = DeploymentState.FAILED; summary = message; updatedAt = now;
    }

    public boolean failIfActiveBefore(Instant cutoff, Instant now, String message) {
        if (state.isTerminal() || updatedAt.isAfter(cutoff)) return false;
        return failIfActive(now, message);
    }

    public boolean failIfActive(Instant now, String message) {
        if (state.isTerminal()) return false;
        state = DeploymentState.FAILED;
        summary = message;
        updatedAt = now;
        return true;
    }

    public boolean expire(Instant now) {
        if (state != DeploymentState.READY || expiresAt == null || expiresAt.isAfter(now)) return false;
        state = DeploymentState.EXPIRED;
        updatedAt = now;
        return true;
    }

    public void restart(Instant now) {
        if (state != DeploymentState.EXPIRED && state != DeploymentState.FAILED) {
            throw new IllegalStateException("只有已失败或已过期 Deployment 可以重新部署: " + state);
        }
        state = DeploymentState.PENDING;
        providerRunId = null;
        endpoint = null;
        artifactUri = null;
        artifactDigest = null;
        summary = null;
        lastCallbackKey = null;
        lastCallbackHash = null;
        expiresAt = null;
        updatedAt = now;
    }

    public boolean callback(DeploymentCallbackCommand command, String payloadHash, Instant now, int ttlSeconds) {
        if (lastCallbackKey != null && lastCallbackKey.equals(command.callbackKey())) {
            if (!lastCallbackHash.equals(payloadHash)) throw new IllegalStateException("回调幂等键对应不同载荷");
            return false;
        }
        if (state.isTerminal()) throw new IllegalStateException("Deployment 已是终态: " + state);
        if (command.status() != DeploymentState.BUILDING && command.status() != DeploymentState.READY
                && command.status() != DeploymentState.FAILED) throw new IllegalArgumentException("非法回调状态");
        state = command.status(); providerRunId = command.providerRunId(); endpoint = command.endpoint();
        artifactUri = command.artifactUri(); artifactDigest = command.artifactDigest(); summary = command.summary();
        lastCallbackKey = command.callbackKey(); lastCallbackHash = payloadHash; updatedAt = now;
        if (state == DeploymentState.READY) expiresAt = now.plusSeconds(ttlSeconds);
        return true;
    }

    public UUID getId() { return id; }
    public UUID getTargetId() { return targetId; }
    public UUID getProfileId() { return profileId; }
    public String getEnvironmentExternalId() { return environmentExternalId; }
    public boolean isInitializationRequested() { return initializationRequested != null && initializationRequested; }
    public String getResolvedCommit() { return resolvedCommit; }
    public String getDeploymentKey() { return deploymentKey; }
    public DeploymentState getState() { return state; }
    public String getProviderRunId() { return providerRunId; }
    public String getEndpoint() { return endpoint; }
    public String getArtifactUri() { return artifactUri; }
    public String getArtifactDigest() { return artifactDigest; }
    public String getSummary() { return summary; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public long getVersion() { return version; }
}
