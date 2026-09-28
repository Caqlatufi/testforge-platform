package io.testforge.cicdgateway.deployment.model;

public record CreateDeploymentProfileCommand(
        String name,
        String serverUrl,
        String jobName,
        String credentialRef,
        String buildConfigDigest,
        int maxConcurrency,
        int ttlSeconds
) { }
