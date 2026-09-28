package io.testforge.cicdgateway.deployment.model;

import java.util.UUID;

public record DeploymentTriggerRequestedEvent(UUID deploymentId) { }
