package io.testforge.casecatalog.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.workflow.compile.model.PublishedWorkflowVersionView;
import io.testforge.casecatalog.workflow.compile.model.WorkflowPublishInput;
import io.testforge.casecatalog.workflow.compile.service.PublishedVersionConflictException;
import io.testforge.casecatalog.workflow.compile.service.WorkflowCompileException;
import io.testforge.casecatalog.workflow.compile.service.WorkflowPublishService;
import io.testforge.casecatalog.workflow.draft.WorkflowDraft;
import io.testforge.casecatalog.workflow.draft.WorkflowDraftGraphValidator;
import io.testforge.casecatalog.workflow.draft.WorkflowDraftValidationException;
import io.testforge.casecatalog.workflow.entity.TestWorkflowEntity;
import io.testforge.casecatalog.workflow.model.CreateWorkflowCommand;
import io.testforge.casecatalog.workflow.model.WorkflowView;
import io.testforge.casecatalog.workflow.repo.TestWorkflowRepository;
import io.testforge.projectcatalog.service.ProjectCatalogException;
import io.testforge.projectcatalog.service.ProjectCatalogNotFoundException;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class TestWorkflowService {

    private final TestWorkflowRepository repository;
    private final ProjectCatalogService projectCatalogService;
    private final WorkflowDraftGraphValidator graphValidator;
    private final WorkflowDraftJsonCodec draftCodec;
    private final WorkflowReferenceResolver referenceResolver;
    private final WorkflowPublishService publishService;

    public TestWorkflowService(
            TestWorkflowRepository repository,
            ProjectCatalogService projectCatalogService,
            ObjectMapper objectMapper,
            WorkflowReferenceResolver referenceResolver,
            WorkflowPublishService publishService
    ) {
        this.repository = repository;
        this.projectCatalogService = projectCatalogService;
        this.graphValidator = new WorkflowDraftGraphValidator();
        this.draftCodec = new WorkflowDraftJsonCodec(objectMapper);
        this.referenceResolver = referenceResolver;
        this.publishService = publishService;
    }

    @Transactional
    public WorkflowView create(UUID projectId, CreateWorkflowCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        UUID targetId = command.targetId() == null
                ? projectCatalogService.requireExecutionTarget(projectId).id()
                : command.targetId();
        requireTargetBelongsToProject(projectId, targetId);
        String name = normalizeName(command.name());
        if (repository.findByProjectIdAndTargetIdAndName(projectId, targetId, name).isPresent()) {
            throw new WorkflowConflictException("同一被测对象下已存在同名 Workflow: " + name);
        }

        UUID workflowId = UUID.randomUUID();
        Instant now = Instant.now();
        var emptyDraft = new WorkflowDraft(workflowId, List.of(), List.of());
        var entity = new TestWorkflowEntity(
                workflowId,
                projectId,
                targetId,
                name,
                draftCodec.write(emptyDraft),
                now
        );
        try {
            return toView(repository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException exception) {
            throw new WorkflowConflictException("同一被测对象下已存在同名 Workflow: " + name);
        }
    }

    @Transactional
    public WorkflowView saveGraph(UUID workflowId, Integer expectedDraftRevision, WorkflowDraft draft) {
        if (workflowId == null || draft == null || !workflowId.equals(draft.workflowId())) {
            throw new WorkflowValidationException("Workflow 路径 ID 与草稿 ID 必须一致");
        }
        graphValidator.validate(draft);
        TestWorkflowEntity entity = requireForUpdate(workflowId);
        if (expectedDraftRevision != null && expectedDraftRevision != entity.getDraftRevision()) {
            throw new WorkflowConflictException(
                    "Workflow 草稿版本已变化，期望 " + expectedDraftRevision
                            + "，实际 " + entity.getDraftRevision()
            );
        }
        entity.saveDraft(draftCodec.write(draft), Instant.now());
        try {
            return toView(repository.saveAndFlush(entity));
        } catch (OptimisticLockingFailureException exception) {
            throw new WorkflowConflictException("Workflow 草稿已被其他请求更新: " + workflowId);
        }
    }

    @Transactional(readOnly = true)
    public WorkflowView get(UUID workflowId) {
        return toView(require(workflowId));
    }

    @Transactional(readOnly = true)
    public List<WorkflowView> list(UUID projectId) {
        projectCatalogService.requireProjectView(projectId);
        return repository.findAllByProjectIdOrderByCreatedAtAsc(projectId).stream().map(this::toView).toList();
    }

    @Transactional
    public PublishedWorkflowVersionView publish(UUID workflowId, UUID requestKey) {
        if (requestKey == null) {
            throw new WorkflowValidationException("requestKey 不能为空");
        }
        TestWorkflowEntity entity = requireForUpdate(workflowId);
        var retried = publishService.findByRequestKey(workflowId, requestKey);
        if (retried.isPresent()) {
            return retried.get();
        }

        WorkflowDraft draft = draftCodec.read(entity.getDraftGraph());
        try {
            graphValidator.validate(draft);
            int version = entity.getLatestVersion() + 1;
            var input = new WorkflowPublishInput(
                    entity.getId(),
                    entity.getProjectId(),
                    entity.getTargetId(),
                    version,
                    referenceResolver.toPublishGraph(draft),
                    referenceResolver.resolve(entity.getId(), draft)
            );
            PublishedWorkflowVersionView published = publishService.publish(input, requestKey, draft);
            entity.markPublished(published.version(), Instant.now());
            repository.saveAndFlush(entity);
            return published;
        } catch (WorkflowDraftValidationException exception) {
            throw exception;
        } catch (PublishedVersionConflictException exception) {
            throw new WorkflowConflictException(exception.getMessage());
        } catch (WorkflowCompileException exception) {
            throw new WorkflowValidationException(exception.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public PublishedWorkflowVersionView getPublished(UUID workflowId, int version) {
        require(workflowId);
        try {
            return publishService.get(workflowId, version);
        } catch (WorkflowCompileException exception) {
            throw new WorkflowNotFoundException(exception.getMessage());
        }
    }

    /**
     * 以已发布拓扑为边界，重新解析当前 Case 定义，供一次 Run 创建不可变执行快照。
     * 旧版本若没有 displaySnapshot，则继续使用历史 compiledSnapshot，避免破坏兼容数据。
     */
    @Transactional(readOnly = true)
    public PublishedWorkflowVersionView resolveForExecution(UUID workflowId, int version) {
        PublishedWorkflowVersionView published = publishService.get(workflowId, version);
        if (published.displaySnapshot() == null) {
            return published;
        }
        WorkflowDraft topology = published.displaySnapshot().graph();
        try {
            var input = new WorkflowPublishInput(
                    published.workflowId(),
                    published.projectId(),
                    published.targetId(),
                    version,
                    referenceResolver.toPublishGraph(topology),
                    referenceResolver.resolve(published.workflowId(), topology)
            );
            var executionSnapshot = publishService.compileExecutionSnapshot(input);
            return new PublishedWorkflowVersionView(
                    published.id(),
                    published.workflowId(),
                    published.projectId(),
                    published.targetId(),
                    published.version(),
                    publishService.executionChecksum(executionSnapshot),
                    executionSnapshot,
                    published.displaySnapshot(),
                    published.publishedAt()
            );
        } catch (WorkflowCompileException exception) {
            throw new WorkflowValidationException("创建执行快照失败: " + exception.getMessage());
        }
    }

    private TestWorkflowEntity require(UUID workflowId) {
        if (workflowId == null) {
            throw new WorkflowValidationException("workflowId 不能为空");
        }
        return repository.findById(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow 不存在: " + workflowId));
    }

    private TestWorkflowEntity requireForUpdate(UUID workflowId) {
        if (workflowId == null) {
            throw new WorkflowValidationException("workflowId 不能为空");
        }
        return repository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow 不存在: " + workflowId));
    }

    private void requireTargetBelongsToProject(UUID projectId, UUID targetId) {
        if (projectId == null || targetId == null) {
            throw new WorkflowValidationException("projectId 和 targetId 不能为空");
        }
        try {
            projectCatalogService.requireProjectView(projectId);
            var target = projectCatalogService.requireTargetView(targetId);
            if (!projectId.equals(target.projectId())) {
                throw new WorkflowValidationException("被测对象不属于指定项目");
            }
        } catch (ProjectCatalogNotFoundException exception) {
            throw new WorkflowNotFoundException(exception.getMessage());
        } catch (ProjectCatalogException exception) {
            throw new WorkflowValidationException(exception.getMessage());
        }
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new WorkflowValidationException("Workflow 名称不能为空");
        }
        String normalized = name.trim();
        if (normalized.length() > 200) {
            throw new WorkflowValidationException("Workflow 名称不能超过 200 个字符");
        }
        return normalized;
    }

    private WorkflowView toView(TestWorkflowEntity entity) {
        return new WorkflowView(
                entity.getId(),
                entity.getProjectId(),
                entity.getTargetId(),
                entity.getName(),
                entity.getDraftRevision(),
                entity.getLatestVersion(),
                draftCodec.read(entity.getDraftGraph()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
