package io.testforge.cicdgateway.deployment.service;

import io.testforge.cicdgateway.deployment.entity.DeploymentProfileEntity;
import io.testforge.cicdgateway.deployment.entity.TargetDeploymentEntity;
import io.testforge.cicdgateway.deployment.model.CreateDeploymentProfileCommand;
import io.testforge.cicdgateway.deployment.model.DeploymentCallbackCommand;
import io.testforge.cicdgateway.deployment.model.DeploymentState;
import io.testforge.cicdgateway.deployment.repo.DeploymentProfileRepository;
import io.testforge.cicdgateway.deployment.repo.TargetDeploymentRepository;
import io.testforge.projectcatalog.model.EnvironmentView;
import io.testforge.projectcatalog.model.TargetType;
import io.testforge.projectcatalog.model.TargetView;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentInitializationServiceTest {
    @Test
    void freezesInitializationRequestAndClearsOneShotFlagOnlyAfterReady() {
        UUID targetId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-23T00:00:00Z");
        var environment = new EnvironmentView(environmentId, targetId, "Windows VM", null,
                "windows-vm", 2, true, true, Map.of(), Map.of());
        var target = new TargetView(targetId, projectId, "Skill Sandbox", TargetType.DESKTOP,
                "https://example.test/skill-sandbox.git", "main", List.of(environment));
        var profile = new DeploymentProfileEntity(profileId, targetId,
                new CreateDeploymentProfileCommand("sandbox", "http://jenkins", "sandbox/main", null,
                        "sha256:" + "0".repeat(64), 2, 600), now);

        DeploymentProfileRepository profiles = mock(DeploymentProfileRepository.class);
        TargetDeploymentRepository deployments = mock(TargetDeploymentRepository.class);
        ProjectCatalogService projects = mock(ProjectCatalogService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(projects.requireTargetView(targetId)).thenReturn(target);
        when(projects.requireEnvironmentForDeployment("windows-vm")).thenReturn(environment);
        when(profiles.findByIdForUpdate(profileId)).thenReturn(Optional.of(profile));
        when(profiles.findById(profileId)).thenReturn(Optional.of(profile));
        when(deployments.findByDeploymentKey(any())).thenReturn(Optional.empty());
        when(deployments.countByProfileIdAndStateIn(any(), any())).thenReturn(0L);
        when(deployments.countByTargetIdAndEnvironmentExternalIdAndStateIn(any(), any(), any()))
                .thenReturn(0L);
        AtomicReference<TargetDeploymentEntity> saved = new AtomicReference<>();
        when(deployments.saveAndFlush(any())).thenAnswer(invocation -> {
            TargetDeploymentEntity entity = invocation.getArgument(0);
            saved.set(entity);
            return entity;
        });

        var service = new DeploymentService(profiles, deployments, projects, events,
                Clock.fixed(now, ZoneOffset.UTC));
        var prepared = service.prepare(targetId, profileId, "a".repeat(40), "windows-vm");

        assertThat(prepared.initializationRequested()).isTrue();
        verify(deployments).countByEnvironmentExternalIdAndStateIn(eq("windows-vm"), any());
        when(deployments.findById(prepared.id())).thenReturn(Optional.of(saved.get()));
        service.callback(prepared.id(), new DeploymentCallbackCommand(UUID.randomUUID(), "build-1",
                DeploymentState.READY, "http://sandbox", null, null, "ready"));

        verify(projects).markEnvironmentInitialized("windows-vm");
    }
}
