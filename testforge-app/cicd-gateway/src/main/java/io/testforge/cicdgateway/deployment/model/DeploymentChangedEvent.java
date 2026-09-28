package io.testforge.cicdgateway.deployment.model;

import java.util.UUID;

public record DeploymentChangedEvent(UUID deploymentId, DeploymentState state) { }
