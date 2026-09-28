package io.testforge.cicdgateway.deployment.service;

import io.testforge.cicdgateway.deployment.model.DeploymentChangedEvent;
import io.testforge.cicdgateway.deployment.model.DeploymentState;
import io.testforge.cicdgateway.deployment.model.DeploymentTriggerRequestedEvent;
import io.testforge.cicdgateway.provider.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DeploymentTriggerListener {
    private final DeploymentService service;
    private final Map<io.testforge.cicdgateway.deployment.model.DeploymentProviderType, DeploymentProvider> providers;
    private final String callbackBaseUrl;

    public DeploymentTriggerListener(DeploymentService service, List<DeploymentProvider> providers,
            @Value("${testforge.cicd.callback-base-url:http://host.docker.internal:8081}") String callbackBaseUrl) {
        this.service = service; this.providers = providers.stream().collect(Collectors.toMap(DeploymentProvider::type, Function.identity()));
        this.callbackBaseUrl = callbackBaseUrl.replaceAll("/+$", "");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void trigger(DeploymentTriggerRequestedEvent event) {
        var deployment = service.requireDeployment(event.deploymentId());
        var profile = service.requireProfile(deployment.getProfileId());
        var target = service.projects().requireTargetView(deployment.getTargetId());
        var testEnvironmentId = deployment.getEnvironmentExternalId() == null
                ? null
                : service.projects().requireEnvironmentForDeployment(deployment.getEnvironmentExternalId()).id();
        DeploymentProvider provider = providers.get(profile.getProvider());
        try {
            DeploymentTriggerResult result = provider.trigger(profile, new DeploymentTriggerRequest(
                    deployment.getId(), target.repositoryUrl(), deployment.getResolvedCommit(),
                    callbackBaseUrl + "/api/v1/deployments/" + deployment.getId() + "/callbacks/jenkins",
                    testEnvironmentId,
                    deployment.getEnvironmentExternalId(),
                    deployment.isInitializationRequested()));
            deployment.triggered(result.providerRunId(), service.clock().instant());
        } catch (RuntimeException error) {
            deployment.triggerFailed(error.getMessage(), service.clock().instant());
        }
        service.deployments().saveAndFlush(deployment);
        if (deployment.getState() == DeploymentState.FAILED) {
            service.publishChanged(deployment);
        }
    }
}
