package io.testforge.testjob.service;

import io.testforge.casecatalog.workflow.compile.model.PublishedWorkflowVersionView;
import io.testforge.casecatalog.workflow.compile.service.WorkflowPublishService;
import io.testforge.cicdgateway.catalog.service.PipelineCatalogService;
import io.testforge.projectcatalog.model.ProjectState;
import io.testforge.projectcatalog.model.EnvironmentPlatform;
import io.testforge.projectcatalog.revision.RevisionSelector;
import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.projectcatalog.revision.ResolvedRevision;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import io.testforge.testjob.entity.TestJobEntity;
import io.testforge.testjob.model.*;
import io.testforge.testjob.repo.TestJobRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class TestJobService {
    static final int NORMAL_PRIORITY = 5;
    static final int URGENT_PRIORITY = 9;
    static final int AUTO_CONCURRENCY_CEILING = 100;

    private final TestJobRepository repository;
    private final ProjectCatalogService projects;
    private final WorkflowPublishService workflows;
    private final PipelineCatalogService pipelines;

    public TestJobService(TestJobRepository repository, ProjectCatalogService projects,
                          WorkflowPublishService workflows, PipelineCatalogService pipelines) {
        this.repository = repository; this.projects = projects; this.workflows = workflows; this.pipelines = pipelines;
    }

    @Transactional
    public TestJobView create(TestJobCommand raw) {
        TestJobCommand command = normalize(raw, null);
        ValidatedStatic validated = validateStatic(command);
        repository.findByProjectIdAndCode(command.projectId(), command.code()).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project 下 Test Job 编码已存在: " + command.code());
        });
        try {
            Instant now = Instant.now();
            return view(repository.saveAndFlush(new TestJobEntity(UUID.randomUUID(), command,
                    validated.workflow().checksum(), validated.revision().commitSha(),
                    validated.commitMessage(), validated.revision().resolvedAt(), now)));
        } catch (DataIntegrityViolationException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project 下 Test Job 编码已存在", duplicate);
        }
    }

    @Transactional
    public TestJobView update(UUID id, long expectedConfigVersion, TestJobCommand raw) {
        TestJobEntity entity = require(id);
        if (entity.getConfigVersion() != expectedConfigVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Test Job 配置版本已变化");
        }
        if (entity.getState() != TestJobState.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已确认测试任务不可修改，请复制为新任务");
        }
        TestJobCommand command = normalize(raw, entity.getCode());
        if (!entity.getProjectId().equals(command.projectId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Test Job 不允许迁移到其他 Project");
        }
        repository.findByProjectIdAndCode(command.projectId(), command.code())
                .filter(other -> !other.getId().equals(id)).ifPresent(other -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Project 下 Test Job 编码已存在");
                });
        ValidatedStatic validated = validateStatic(command);
        entity.update(command, validated.workflow().checksum(), validated.revision().commitSha(),
                validated.commitMessage(), validated.revision().resolvedAt(), Instant.now());
        return view(repository.saveAndFlush(entity));
    }

    @Transactional
    public TestJobView copy(UUID id) {
        TestJobEntity source = require(id);
        PinnedRevision pinned = ensurePinned(source);
        List<String> names = repository.findAllByProjectIdOrderByCreatedAtAsc(source.getProjectId()).stream()
                .map(TestJobEntity::getName).toList();
        String name = nextCopyName(source.getName(), names);
        TestJobCommand command = new TestJobCommand(source.getProjectId(), UUID.randomUUID().toString(), name,
                source.getDescription(), source.getWorkflowId(), source.getWorkflowVersion(),
                source.getRevisionType(), source.getRevisionValue(), source.getPipelineExternalId(), null,
                source.getPlatform(), source.getPriority(), source.getProcessConcurrency(), source.getDeviceConcurrency());
        Instant now = Instant.now();
        return view(repository.saveAndFlush(new TestJobEntity(UUID.randomUUID(), command,
                source.getWorkflowChecksum(), pinned.revision().commitSha(), pinned.commitMessage(),
                pinned.revision().resolvedAt(), now)));
    }

    @Transactional
    public TestJobView activate(UUID id, long expectedConfigVersion) {
        TestJobEntity entity = requireVersion(id, expectedConfigVersion);
        var validated = validateReferences(entity);
        entity.activate(validated.pipeline().name(), validated.pipeline().revision(),
                validated.environment().name(), Instant.now());
        return view(repository.saveAndFlush(entity));
    }

    @Transactional
    public TestJobView disable(UUID id, long expectedConfigVersion) {
        TestJobEntity entity = requireVersion(id, expectedConfigVersion);
        entity.disable(Instant.now());
        return view(repository.saveAndFlush(entity));
    }

    @Transactional
    public PreparedTestJobLaunch prepareLaunch(UUID id) {
        TestJobEntity entity = require(id);
        if (entity.getState() != TestJobState.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有 ACTIVE Test Job 可以启动");
        }
        var selection = validateReferences(entity);
        PublishedWorkflowVersionView workflow = workflows.get(entity.getWorkflowId(), entity.getWorkflowVersion());
        var project = projects.requireProjectView(entity.getProjectId());
        PinnedRevision pinned = ensurePinned(entity);
        var revision = pinned.revision();
        TestJobSnapshot snapshot = new TestJobSnapshot(entity.getId(), entity.getName(), entity.getConfigVersion(),
                project.id(), project.name(), project.repositoryUrl(), workflow.workflowId(), workflow.version(),
                workflow.checksum(), revision.requestedType(), revision.requestedValue(), revision.commitSha(),
                selection.pipeline().externalId(), selection.pipeline().provider(), selection.pipeline().name(),
                selection.pipeline().revision(),
                selection.environment().externalId(), selection.environment().name(),
                selection.environment().platform(), selection.environment().resourcePoolKey(), entity.getPriority(),
                entity.getProcessConcurrency(), entity.getDeviceConcurrency(), Instant.now());
        return new PreparedTestJobLaunch(view(entity), snapshot, workflow, revision,
                selection.deploymentProfileId(), selection.environment().providerKey());
    }

    @Transactional
    public TestJobView get(UUID id) {
        TestJobEntity entity = require(id);
        ensurePinned(entity);
        return view(entity);
    }

    @Transactional
    public List<TestJobView> list(UUID projectId) {
        return (projectId == null ? repository.findAllByOrderByCreatedAtDesc()
                : repository.findAllByProjectIdOrderByCreatedAtDesc(projectId)).stream()
                .peek(this::ensurePinned).map(this::view).toList();
    }

    private ValidatedStatic validateStatic(TestJobCommand command) {
        var project = projects.requireProjectView(command.projectId());
        if (project.state() != ProjectState.ACTIVE) throw new ResponseStatusException(HttpStatus.CONFLICT, "Project 未激活");
        if (project.repositoryUrl() == null || project.defaultBranch() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project 未配置 Git 仓库和默认分支");
        }
        PublishedWorkflowVersionView workflow = workflows.get(command.workflowId(), command.workflowVersion());
        if (!workflow.projectId().equals(command.projectId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow 不属于指定 Project");
        }
        PinnedRevision pinned = resolvePinnedRevision(command.projectId(),
                new RevisionSelector(command.revisionType(), command.revisionValue()));
        return new ValidatedStatic(workflow, pinned.revision(), pinned.commitMessage());
    }

    private PipelineCatalogService.Selection validateReferences(TestJobEntity entity) {
        PublishedWorkflowVersionView workflow = workflows.get(entity.getWorkflowId(), entity.getWorkflowVersion());
        if (!workflow.projectId().equals(entity.getProjectId())
                || !workflow.checksum().equals(entity.getWorkflowChecksum())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow 发布版本已经失效");
        }
        return pipelines.validateForPlatform(entity.getProjectId(), entity.getPipelineExternalId(),
                entity.getPlatform(),
                new RevisionSelector(entity.getRevisionType(), entity.getRevisionValue()));
    }

    private TestJobEntity requireVersion(UUID id, long expectedConfigVersion) {
        TestJobEntity entity = require(id);
        if (entity.getConfigVersion() != expectedConfigVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Test Job 配置版本已变化");
        }
        return entity;
    }

    private TestJobEntity require(UUID id) {
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Test Job 不存在: " + id));
    }

    private TestJobCommand normalize(TestJobCommand command, String existingCode) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.projectId() == null || command.workflowId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project 和已发布 Workflow 不能为空");
        }
        String code = trim(command.code());
        if (code == null) code = existingCode == null ? UUID.randomUUID().toString() : existingCode;
        if (!code.matches("^[a-z0-9][a-z0-9-]{1,62}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Test Job 编码格式错误");
        }
        String name = trim(command.name());
        if (name == null || name.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "名称不能为空且不能超过 200 字符");
        int workflowVersion = command.workflowVersion();
        if (workflowVersion < 1) {
            var versions = workflows.list(command.workflowId());
            if (versions.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow 尚无已发布版本");
            }
            workflowVersion = versions.getLast().version();
        }
        RevisionType type = command.revisionType() == null ? RevisionType.DEFAULT_BRANCH : command.revisionType();
        String value = trim(command.revisionValue());
        if (type != RevisionType.DEFAULT_BRANCH && value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非默认分支版本策略必须填写引用值");
        }
        String pipelineExternalId = trim(command.pipelineExternalId());
        if (pipelineExternalId == null) {
            pipelineExternalId = pipelines.list(command.projectId()).stream()
                    .filter(io.testforge.cicdgateway.catalog.model.PipelineRef::enabled)
                    .sorted(java.util.Comparator
                            .comparing(io.testforge.cicdgateway.catalog.model.PipelineRef::name)
                            .thenComparing(item -> item.externalId().toString()))
                    .findFirst()
                    .map(item -> item.externalId().toString())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "Project 没有已启用的 Pipeline"));
        }
        if (command.priority() != NORMAL_PRIORITY && command.priority() != URGENT_PRIORITY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "优先级只允许普通或紧急");
        }
        EnvironmentPlatform platform = command.platform() == null ? EnvironmentPlatform.WINDOWS : command.platform();
        return new TestJobCommand(command.projectId(), code, name, trim(command.description()), command.workflowId(),
                workflowVersion, type, value, pipelineExternalId,
                null, platform, command.priority(), AUTO_CONCURRENCY_CEILING,
                AUTO_CONCURRENCY_CEILING);
    }

    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private PinnedRevision ensurePinned(TestJobEntity entity) {
        if (entity.getResolvedCommit() != null && entity.getRevisionResolvedAt() != null) {
            return new PinnedRevision(new ResolvedRevision(entity.getRevisionType(), entity.getRevisionValue(),
                    entity.getResolvedCommit(), entity.getRevisionResolvedAt()), entity.getCommitMessage());
        }
        PinnedRevision pinned = resolvePinnedRevision(entity.getProjectId(),
                new RevisionSelector(entity.getRevisionType(), entity.getRevisionValue()));
        entity.pinRevision(pinned.revision().commitSha(), pinned.commitMessage(),
                pinned.revision().resolvedAt(), Instant.now());
        repository.saveAndFlush(entity);
        return pinned;
    }

    private PinnedRevision resolvePinnedRevision(UUID projectId, RevisionSelector selector) {
        ResolvedRevision revision = projects.resolveProjectRevision(projectId, selector);
        String message = null;
        RevisionType catalogType = revision.requestedType() == RevisionType.DEFAULT_BRANCH
                ? RevisionType.BRANCH : revision.requestedType();
        if (catalogType == RevisionType.BRANCH || catalogType == RevisionType.TAG) {
            try {
                message = projects.listProjectRevisions(projectId, catalogType).stream()
                        .filter(option -> option.commitSha().equalsIgnoreCase(revision.commitSha()))
                        .filter(option -> revision.requestedValue() == null
                                || option.name().equals(revision.requestedValue()))
                        .map(io.testforge.projectcatalog.revision.RevisionOption::commitMessage)
                        .findFirst().orElse(null);
            } catch (RuntimeException ignored) {
                // Commit 已完成权威解析；提交描述仅用于展示，不阻断任务保存。
            }
        }
        return new PinnedRevision(revision, message);
    }

    private String nextCopyName(String source, List<String> existing) {
        String root = source.replaceFirst("-\\d+$", "");
        for (int suffix = 1; suffix < 10_000; suffix++) {
            String marker = "-" + suffix;
            String base = root.substring(0, Math.min(root.length(), 200 - marker.length()));
            String candidate = base + marker;
            if (!existing.contains(candidate)) return candidate;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "无法生成可用的复制任务名称");
    }

    private TestJobView view(TestJobEntity e) {
        return new TestJobView(e.getId(), e.getProjectId(), e.getCode(), e.getName(), e.getDescription(),
                e.getWorkflowId(), e.getWorkflowVersion(), e.getWorkflowChecksum(), e.getRevisionType(),
                e.getRevisionValue(), e.getResolvedCommit(), e.getCommitMessage(), e.getRevisionResolvedAt(),
                e.getPipelineExternalId(), e.getPipelineName(), e.getPipelineRevision(),
                e.getEnvironmentExternalId(), e.getEnvironmentName(), e.getPlatform(), e.getPriority(), e.getProcessConcurrency(),
                e.getDeviceConcurrency(), e.getState(), e.getConfigVersion(), e.getPersistenceVersion(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private record PinnedRevision(ResolvedRevision revision, String commitMessage) { }
    private record ValidatedStatic(PublishedWorkflowVersionView workflow, ResolvedRevision revision,
                                   String commitMessage) { }
}
