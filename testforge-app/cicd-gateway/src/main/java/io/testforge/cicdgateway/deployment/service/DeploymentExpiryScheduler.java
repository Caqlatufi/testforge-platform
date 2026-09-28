package io.testforge.cicdgateway.deployment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class DeploymentExpiryScheduler {
    private final DeploymentService service;
    private final Duration activeTimeout;

    public DeploymentExpiryScheduler(DeploymentService service,
            @Value("${testforge.cicd.active-timeout:PT30M}") Duration activeTimeout) {
        this.service = service;
        this.activeTimeout = activeTimeout;
    }

    @Scheduled(fixedDelayString = "${testforge.cicd.expiry-scan-delay:PT30S}")
    public void expireReadyDeployments() {
        service.expireReadyDeployments();
        service.failStaleActiveDeployments(activeTimeout);
    }
}
