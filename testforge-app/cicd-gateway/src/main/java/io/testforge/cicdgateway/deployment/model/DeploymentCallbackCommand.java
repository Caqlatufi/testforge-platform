package io.testforge.cicdgateway.deployment.model;

import java.util.UUID;

public record DeploymentCallbackCommand(
        UUID callbackKey,
        String providerRunId,
        DeploymentState status,
        String endpoint,
        String artifactUri,
        String artifactDigest,
        String summary
) { }
