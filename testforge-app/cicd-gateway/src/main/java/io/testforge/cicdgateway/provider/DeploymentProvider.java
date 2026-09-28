package io.testforge.cicdgateway.provider;

import io.testforge.cicdgateway.deployment.entity.DeploymentProfileEntity;
import io.testforge.cicdgateway.deployment.model.DeploymentProviderType;

public interface DeploymentProvider {
    DeploymentProviderType type();
    DeploymentTriggerResult trigger(DeploymentProfileEntity profile, DeploymentTriggerRequest request);
}
