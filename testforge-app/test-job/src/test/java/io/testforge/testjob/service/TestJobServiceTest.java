package io.testforge.testjob.service;

import io.testforge.casecatalog.workflow.compile.model.CompiledWorkflowSnapshot;
import io.testforge.casecatalog.workflow.compile.model.PublishedWorkflowVersionView;
import io.testforge.casecatalog.workflow.compile.service.WorkflowPublishService;
import io.testforge.cicdgateway.catalog.model.PipelineEnvironmentRef;
import io.testforge.cicdgateway.catalog.model.PipelineRef;
import io.testforge.cicdgateway.catalog.service.PipelineCatalogService;
import io.testforge.projectcatalog.model.ProjectState;
import io.testforge.projectcatalog.model.ProjectView;
import io.testforge.projectcatalog.model.TargetType;
import io.testforge.projectcatalog.revision.ResolvedRevision;
import io.testforge.projectcatalog.revision.RevisionOption;
import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import io.testforge.testjob.entity.TestJobEntity;
import io.testforge.testjob.model.TestJobCommand;
import io.testforge.testjob.model.TestJobState;
import io.testforge.testjob.repo.TestJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestJobServiceTest {
    @Mock TestJobRepository repository;
    @Mock ProjectCatalogService projects;
    @Mock WorkflowPublishService workflows;
    @Mock PipelineCatalogService pipelines;
    TestJobService service;
    UUID projectId, workflowId, targetId, pipelineId;

    @BeforeEach
    void setUp() {
        service = new TestJobService(repository, projects, workflows, pipelines);
        projectId = UUID.randomUUID(); workflowId = UUID.randomUUID(); targetId = UUID.randomUUID(); pipelineId = UUID.randomUUID();
        lenient().when(projects.requireProjectView(projectId)).thenReturn(new ProjectView(projectId, "Skill Sandbox", "skill-sandbox",
                ProjectState.ACTIVE, TargetType.DESKTOP, "E:/repo", "main", "JENKINS", "http://localhost:8080",
                null, "local", List.of()));
        lenient().when(workflows.get(workflowId, 1)).thenReturn(new PublishedWorkflowVersionView(UUID.randomUUID(), workflowId,
                projectId, targetId, 1, "sha256:" + "a".repeat(64),
                new CompiledWorkflowSnapshot(1, workflowId, 1, projectId, targetId, List.of(), List.of(), List.of()),
                Instant.now()));
        lenient().when(repository.findByProjectIdAndCode(eq(projectId), anyString())).thenReturn(Optional.empty());
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(projects.resolveProjectRevision(eq(projectId), any())).thenReturn(new ResolvedRevision(
                RevisionType.BRANCH, "main", "c".repeat(40), Instant.parse("2026-09-24T00:00:00Z")));
        lenient().when(projects.listProjectRevisions(projectId, RevisionType.BRANCH)).thenReturn(List.of(
                new RevisionOption(RevisionType.BRANCH, "main", "c".repeat(40),
                        "fixed commit", Instant.parse("2026-09-24T00:00:00Z"), true)));
    }

    @Test
    void shouldCreateActivateAndFreezeLaunchSnapshot() {
        var created = service.create(command());
        assertThat(created.state()).isEqualTo(TestJobState.DRAFT);
        assertThat(created.resolvedCommit()).isEqualTo("c".repeat(40));
        assertThat(created.commitMessage()).isEqualTo("fixed commit");
        when(repository.findById(created.id())).thenReturn(Optional.of(entity(created.id())));
        var selection = new PipelineCatalogService.Selection(
                new PipelineRef(pipelineId, "deploy-sandbox", "JENKINS", "http://localhost:8080",
                        "deploy-sandbox", "sha256:" + "b".repeat(64), true),
                new PipelineEnvironmentRef("windows-vm", "Windows VM", "windows-vm"), pipelineId);
        when(pipelines.validateForPlatform(any(), any(), any(), any())).thenReturn(selection);
        var activated = service.activate(created.id(), 1);
        assertThat(activated.state()).isEqualTo(TestJobState.ACTIVE);
        var launch = service.prepareLaunch(created.id());
        assertThat(launch.snapshot().testJobId()).isEqualTo(created.id());
        assertThat(launch.snapshot().pipelineId()).isEqualTo(pipelineId);
        assertThat(launch.snapshot().resolvedCommit()).isEqualTo("c".repeat(40));
        verify(projects, times(1)).resolveProjectRevision(eq(projectId), any());
        assertThat(launch.deploymentProfileId()).isEqualTo(pipelineId);
        assertThat(launch.providerEnvironmentKey()).isEqualTo("windows-vm");
    }

    @Test
    void shouldResolveSystemManagedDefaults() {
        var latest = new PublishedWorkflowVersionView(UUID.randomUUID(), workflowId,
                projectId, targetId, 2, "sha256:" + "d".repeat(64),
                new CompiledWorkflowSnapshot(1, workflowId, 2, projectId, targetId,
                        List.of(), List.of(), List.of()), Instant.now());
        when(workflows.list(workflowId)).thenReturn(List.of(latest));
        when(workflows.get(workflowId, 2)).thenReturn(latest);
        var defaultPipeline = new PipelineRef(pipelineId, "deploy-sandbox", "JENKINS",
                "http://localhost:8080", "deploy-sandbox", "sha256:" + "e".repeat(64), true);
        when(pipelines.list(projectId)).thenReturn(List.of(defaultPipeline));

        var created = service.create(new TestJobCommand(projectId, null, "自动默认任务", null,
                workflowId, 0, RevisionType.DEFAULT_BRANCH, null, null, null,
                5, 0, 0));

        UUID.fromString(created.code());
        assertThat(created.workflowVersion()).isEqualTo(2);
        assertThat(created.pipelineExternalId()).isEqualTo(pipelineId.toString());
        assertThat(created.priority()).isEqualTo(5);
        assertThat(created.processConcurrency()).isEqualTo(100);
        assertThat(created.deviceConcurrency()).isEqualTo(100);
    }

    @Test
    void shouldRejectMissingDefaultPipelineAndUnsupportedPriority() {
        var latest = new PublishedWorkflowVersionView(UUID.randomUUID(), workflowId,
                projectId, targetId, 2, "sha256:" + "f".repeat(64),
                new CompiledWorkflowSnapshot(1, workflowId, 2, projectId, targetId,
                        List.of(), List.of(), List.of()), Instant.now());
        when(workflows.list(workflowId)).thenReturn(List.of(latest));
        when(pipelines.list(projectId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(new TestJobCommand(projectId, null, "无 Pipeline", null,
                workflowId, 0, RevisionType.DEFAULT_BRANCH, null, null, null,
                5, 0, 0)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("没有已启用的 Pipeline");

        assertThatThrownBy(() -> service.create(new TestJobCommand(projectId, null, "非法优先级", null,
                workflowId, 1, RevisionType.DEFAULT_BRANCH, null, pipelineId.toString(), null,
                7, 0, 0)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("只允许普通或紧急");
    }

    @Test
    void shouldRejectEditingConfirmedTaskAndCopyPinnedConfiguration() {
        TestJobEntity source = entity(UUID.randomUUID());
        TestJobEntity firstCopy = new TestJobEntity(UUID.randomUUID(), new TestJobCommand(projectId, "copy-1",
                "主流程冒烟-1", "demo", workflowId, 1, RevisionType.DEFAULT_BRANCH, null,
                pipelineId.toString(), null, 5, 0, 0), "sha256:" + "a".repeat(64),
                "c".repeat(40), "fixed commit", Instant.parse("2026-09-24T00:00:00Z"), Instant.now());
        when(repository.findById(source.getId())).thenReturn(Optional.of(source));
        when(repository.findAllByProjectIdOrderByCreatedAtAsc(projectId)).thenReturn(List.of(source, firstCopy));

        assertThatThrownBy(() -> service.update(source.getId(), source.getConfigVersion(), command()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("复制为新任务");

        var copy = service.copy(source.getId());
        assertThat(copy.name()).isEqualTo("主流程冒烟-2");
        assertThat(copy.state()).isEqualTo(TestJobState.DRAFT);
        assertThat(copy.resolvedCommit()).isEqualTo(source.getResolvedCommit());
        assertThat(copy.workflowVersion()).isEqualTo(source.getWorkflowVersion());
    }

    @Test
    void shouldListNewestJobsFirst() {
        Instant olderTime = Instant.parse("2026-09-24T00:00:00Z");
        Instant newerTime = Instant.parse("2026-09-25T00:00:00Z");
        TestJobEntity newer = new TestJobEntity(UUID.randomUUID(), new TestJobCommand(projectId, "newer",
                "最新任务", "demo", workflowId, 1, RevisionType.DEFAULT_BRANCH, null,
                pipelineId.toString(), null, 5, 0, 0), "sha256:" + "a".repeat(64),
                "c".repeat(40), "fixed commit", newerTime, newerTime);
        TestJobEntity older = new TestJobEntity(UUID.randomUUID(), new TestJobCommand(projectId, "older",
                "较早任务", "demo", workflowId, 1, RevisionType.DEFAULT_BRANCH, null,
                pipelineId.toString(), null, 5, 0, 0), "sha256:" + "a".repeat(64),
                "c".repeat(40), "fixed commit", olderTime, olderTime);
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(newer, older));

        assertThat(service.list(null)).extracting(view -> view.name())
                .containsExactly("最新任务", "较早任务");
        verify(repository).findAllByOrderByCreatedAtDesc();
    }

    private TestJobCommand command() {
        return new TestJobCommand(projectId, "smoke", "主流程冒烟", "demo", workflowId, 1,
                RevisionType.DEFAULT_BRANCH, null, pipelineId.toString(), "windows-vm", 5, 3, 1);
    }

    private TestJobEntity entity(UUID id) {
        TestJobEntity entity = new TestJobEntity(id, command(), "sha256:" + "a".repeat(64),
                "c".repeat(40), "fixed commit", Instant.parse("2026-09-24T00:00:00Z"), Instant.now());
        entity.activate("deploy-sandbox", "sha256:" + "b".repeat(64), "windows-vm", Instant.now());
        return entity;
    }
}
