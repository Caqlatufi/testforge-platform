package io.testforge.cicdgateway.provider;

import java.util.UUID;

public record DeploymentTriggerRequest(
        UUID deploymentId,
        String repositoryUrl,
        String resolvedCommit,
        String callbackUrl,
        UUID testEnvironmentId,
        String environmentExternalId,
        boolean initializeEnvironment
) {
    public DeploymentTriggerRequest(UUID deploymentId, String repositoryUrl, String resolvedCommit,
                                    String callbackUrl) {
        this(deploymentId, repositoryUrl, resolvedCommit, callbackUrl, null, null, false);
    }

    public DeploymentTriggerRequest(UUID deploymentId, String repositoryUrl, String resolvedCommit,
                                    String callbackUrl, String environmentExternalId) {
        this(deploymentId, repositoryUrl, resolvedCommit, callbackUrl, null, environmentExternalId, false);
    }
}
