package io.testforge.cicdgateway.deployment.model;

import java.time.Instant;
import java.util.UUID;

public record TargetDeploymentView(
        UUID id,
        UUID targetId,
        UUID profileId,
        String resolvedCommit,
        String deploymentKey,
        DeploymentState state,
        String providerRunId,
        String endpoint,
        String artifactUri,
        String artifactDigest,
        String summary,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        long version,
        String environmentExternalId,
        boolean initializationRequested
) {
    public TargetDeploymentView(UUID id, UUID targetId, UUID profileId, String resolvedCommit,
                                String deploymentKey, DeploymentState state, String providerRunId,
                                String endpoint, String artifactUri, String artifactDigest, String summary,
                                Instant createdAt, Instant updatedAt, Instant expiresAt, long version) {
        this(id, targetId, profileId, resolvedCommit, deploymentKey, state, providerRunId, endpoint,
                artifactUri, artifactDigest, summary, createdAt, updatedAt, expiresAt, version, null, false);
    }
}
