package io.testforge.cicdgateway.deployment.model;

import java.time.Instant;
import java.util.UUID;

public record DeploymentProfileView(
        UUID id,
        UUID targetId,
        String name,
        DeploymentProviderType provider,
        String serverUrl,
        String jobName,
        String credentialRef,
        String buildConfigDigest,
        int maxConcurrency,
        int ttlSeconds,
        boolean enabled,
        Instant createdAt
) { }
