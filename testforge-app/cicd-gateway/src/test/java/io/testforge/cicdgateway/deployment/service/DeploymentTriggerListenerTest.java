package io.testforge.cicdgateway.deployment.service;

import io.testforge.cicdgateway.deployment.entity.DeploymentProfileEntity;
import io.testforge.cicdgateway.deployment.entity.TargetDeploymentEntity;
import io.testforge.cicdgateway.deployment.model.DeploymentProviderType;
import io.testforge.cicdgateway.deployment.model.DeploymentTriggerRequestedEvent;
import io.testforge.cicdgateway.deployment.repo.TargetDeploymentRepository;
import io.testforge.cicdgateway.provider.DeploymentProvider;
import io.testforge.cicdgateway.provider.DeploymentTriggerRequest;
import io.testforge.cicdgateway.provider.DeploymentTriggerResult;
import io.testforge.projectcatalog.model.EnvironmentPlatform;
import io.testforge.projectcatalog.model.EnvironmentView;
import io.testforge.projectcatalog.model.TargetType;
import io.testforge.projectcatalog.model.TargetView;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentTriggerListenerTest {
    @Test
    void resolvesTheGlobalEnvironmentIdForTheJenkinsContract() {
        UUID deploymentId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        var deployment = mock(TargetDeploymentEntity.class);
        var profile = mock(DeploymentProfileEntity.class);
        var service = mock(DeploymentService.class);
        var projects = mock(ProjectCatalogService.class);
        var deployments = mock(TargetDeploymentRepository.class);
        var provider = mock(DeploymentProvider.class);

        when(service.requireDeployment(deploymentId)).thenReturn(deployment);
        when(service.requireProfile(profileId)).thenReturn(profile);
        when(service.projects()).thenReturn(projects);
        when(service.deployments()).thenReturn(deployments);
        when(service.clock()).thenReturn(Clock.systemUTC());
        when(deployment.getProfileId()).thenReturn(profileId);
        when(deployment.getTargetId()).thenReturn(targetId);
        when(deployment.getEnvironmentExternalId()).thenReturn("windows-vm");
        when(deployment.getId()).thenReturn(deploymentId);
        when(deployment.getResolvedCommit()).thenReturn("a".repeat(40));
        when(profile.getProvider()).thenReturn(DeploymentProviderType.JENKINS);
        when(projects.requireTargetView(targetId)).thenReturn(new TargetView(targetId, projectId,
                "Skill Sandbox", TargetType.DESKTOP, "https://example.test/sandbox.git", "main", List.of()));
        when(projects.requireEnvironmentForDeployment("windows-vm")).thenReturn(new EnvironmentView(
                environmentId, null, "Windows VM Pool", null, "windows-vm", EnvironmentPlatform.WINDOWS,
                "windows-vm", 2, true, false, Map.of(), Map.of()));
        when(provider.type()).thenReturn(DeploymentProviderType.JENKINS);
        when(provider.trigger(org.mockito.ArgumentMatchers.eq(profile), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new DeploymentTriggerResult("jenkins:queue/42"));

        new DeploymentTriggerListener(service, List.of(provider), "http://host.docker.internal:8081")
                .trigger(new DeploymentTriggerRequestedEvent(deploymentId));

        var request = ArgumentCaptor.forClass(DeploymentTriggerRequest.class);
        verify(provider).trigger(org.mockito.ArgumentMatchers.eq(profile), request.capture());
        assertThat(request.getValue().testEnvironmentId()).isEqualTo(environmentId);
        assertThat(request.getValue().environmentExternalId()).isEqualTo("windows-vm");
        verify(deployments).saveAndFlush(deployment);
    }
}
