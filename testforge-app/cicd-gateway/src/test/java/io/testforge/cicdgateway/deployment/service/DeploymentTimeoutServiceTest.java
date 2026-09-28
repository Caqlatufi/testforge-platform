package io.testforge.cicdgateway.deployment.service;

import io.testforge.cicdgateway.deployment.entity.TargetDeploymentEntity;
import io.testforge.cicdgateway.deployment.model.DeploymentChangedEvent;
import io.testforge.cicdgateway.deployment.model.DeploymentState;
import io.testforge.cicdgateway.deployment.repo.DeploymentProfileRepository;
import io.testforge.cicdgateway.deployment.repo.TargetDeploymentRepository;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentTimeoutServiceTest {
    @Test
    void failsStaleActiveDeploymentAndPublishesChange() {
        Instant createdAt = Instant.parse("2026-09-26T00:00:00Z");
        Instant now = createdAt.plus(Duration.ofMinutes(31));
        var deployment = new TargetDeploymentEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(40), "sha256:" + "b".repeat(64), createdAt);
        deployment.triggered("queue/64", createdAt.plusSeconds(1));

        TargetDeploymentRepository deployments = mock(TargetDeploymentRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(deployments.findAllByStateInAndUpdatedAtLessThanEqual(any(), any())).thenReturn(List.of(deployment));
        var service = new DeploymentService(mock(DeploymentProfileRepository.class), deployments,
                mock(ProjectCatalogService.class), events, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.failStaleActiveDeployments(Duration.ofMinutes(30))).isEqualTo(1);
        assertThat(deployment.getState()).isEqualTo(DeploymentState.FAILED);
        assertThat(deployment.getSummary()).contains("PT30M");
        verify(deployments).save(deployment);
        verify(events).publishEvent(any(DeploymentChangedEvent.class));
    }
}
