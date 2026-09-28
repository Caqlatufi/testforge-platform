package io.testforge.casecatalog.workflow.compile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.workflow.compile.entity.PublishedWorkflowVersionEntity;
import io.testforge.casecatalog.workflow.compile.model.PublishedWorkflowVersionView;
import io.testforge.casecatalog.workflow.compile.model.CompiledWorkflowSnapshot;
import io.testforge.casecatalog.workflow.compile.model.WorkflowPublishInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowDisplaySnapshot;
import io.testforge.casecatalog.workflow.draft.WorkflowDraft;
import io.testforge.casecatalog.workflow.compile.repo.PublishedWorkflowVersionRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 发布固定 Workflow 版本。已存在的相同内容视为幂等重试，不同内容永远不能覆盖。
 */
public class WorkflowPublishService {

    private final PublishedWorkflowVersionRepository repository;
    private final WorkflowSnapshotCompiler compiler;
    private final WorkflowSnapshotJsonCodec codec;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;
    private final ObjectMapper objectMapper;
    private static final Pattern DISPLAY_NODE = Pattern.compile("/node:([0-9a-fA-F-]{36})");

    public WorkflowPublishService(
            PublishedWorkflowVersionRepository repository,
            ObjectMapper objectMapper
    ) {
        this(repository, new WorkflowSnapshotCompiler(), new WorkflowSnapshotJsonCodec(objectMapper),
                Clock.systemUTC(), UUID::randomUUID, objectMapper);
    }

    public WorkflowPublishService(
            PublishedWorkflowVersionRepository repository,
            WorkflowSnapshotCompiler compiler,
            WorkflowSnapshotJsonCodec codec,
            Clock clock,
            Supplier<UUID> idGenerator
    ) {
        this(repository, compiler, codec, clock, idGenerator, new ObjectMapper());
    }

    public WorkflowPublishService(
            PublishedWorkflowVersionRepository repository,
            WorkflowSnapshotCompiler compiler,
            WorkflowSnapshotJsonCodec codec,
            Clock clock,
            Supplier<UUID> idGenerator,
            ObjectMapper objectMapper
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.compiler = Objects.requireNonNull(compiler, "compiler must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Transactional
    public PublishedWorkflowVersionView publish(WorkflowPublishInput input) {
        return publish(input, null);
    }

    @Transactional
    public PublishedWorkflowVersionView publish(WorkflowPublishInput input, UUID requestKey) {
        return publish(input, requestKey, null);
    }

    @Transactional
    public PublishedWorkflowVersionView publish(WorkflowPublishInput input, UUID requestKey, WorkflowDraft displayDraft) {
        if (input == null) {
            throw new WorkflowCompileException("发布输入不能为空");
        }
        if (requestKey != null) {
            var retried = repository.findByWorkflowIdAndRequestKey(input.workflowId(), requestKey);
            if (retried.isPresent()) {
                return toView(retried.get());
            }
        }
        var snapshot = compiler.compile(input);
        String snapshotJson = codec.write(snapshot);
        String checksum = codec.checksum(snapshotJson);
        String displayJson = displayDraft == null ? null : writeDisplay(displayDraft, snapshot);

        var existing = repository.findByWorkflowIdAndVersion(input.workflowId(), input.version());
        if (existing.isPresent()) {
            return requireSameContent(existing.get(), checksum);
        }

        var entity = new PublishedWorkflowVersionEntity(
                idGenerator.get(),
                input.workflowId(),
                input.projectId(),
                input.targetId(),
                input.version(),
                checksum,
                snapshotJson,
                displayJson,
                Instant.now(clock),
                requestKey
        );
        return toView(repository.saveAndFlush(entity));
    }

    @Transactional(readOnly = true)
    public Optional<PublishedWorkflowVersionView> findByRequestKey(UUID workflowId, UUID requestKey) {
        if (workflowId == null || requestKey == null) {
            return Optional.empty();
        }
        return repository.findByWorkflowIdAndRequestKey(workflowId, requestKey).map(this::toView);
    }

    @Transactional(readOnly = true)
    public PublishedWorkflowVersionView get(UUID workflowId, int version) {
        if (workflowId == null || version < 1) {
            throw new WorkflowCompileException("workflowId 不能为空且版本必须大于 0");
        }
        return repository.findByWorkflowIdAndVersion(workflowId, version)
                .map(this::toView)
                .orElseThrow(() -> new PublishedVersionNotFoundException(
                        "已发布 Workflow 版本不存在: " + workflowId + "@" + version
                ));
    }

    @Transactional(readOnly = true)
    public List<PublishedWorkflowVersionView> list(UUID workflowId) {
        if (workflowId == null) {
            throw new WorkflowCompileException("workflowId 不能为空");
        }
        return repository.findAllByWorkflowIdOrderByVersionAsc(workflowId).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 为一次执行生成瞬时编译结果。该快照不创建新的 WorkflowVersion，调用方必须把它固化到 Run。
     */
    public CompiledWorkflowSnapshot compileExecutionSnapshot(WorkflowPublishInput input) {
        return compiler.compile(input);
    }

    public String executionChecksum(CompiledWorkflowSnapshot snapshot) {
        return codec.checksum(codec.write(snapshot));
    }

    private PublishedWorkflowVersionView requireSameContent(
            PublishedWorkflowVersionEntity existing,
            String requestedChecksum
    ) {
        if (!existing.getChecksum().equals(requestedChecksum)) {
            throw new PublishedVersionConflictException(
                    "Workflow 已发布版本不可修改: " + existing.getWorkflowId() + "@" + existing.getVersion()
            );
        }
        return toView(existing);
    }

    private PublishedWorkflowVersionView toView(PublishedWorkflowVersionEntity entity) {
        return new PublishedWorkflowVersionView(
                entity.getId(),
                entity.getWorkflowId(),
                entity.getProjectId(),
                entity.getTargetId(),
                entity.getVersion(),
                entity.getChecksum(),
                codec.read(entity.getCompiledSnapshot()),
                readDisplay(entity.getDisplaySnapshot()),
                entity.getPublishedAt()
        );
    }

    private String writeDisplay(WorkflowDraft draft,
                                io.testforge.casecatalog.workflow.compile.model.CompiledWorkflowSnapshot compiled) {
        Map<UUID, List<UUID>> mapping = new LinkedHashMap<>();
        for (var node : draft.nodes()) mapping.put(node.id(), new ArrayList<>());
        for (var compiledNode : compiled.nodes()) {
            var matcher = DISPLAY_NODE.matcher(compiledNode.sourcePath());
            if (matcher.find()) {
                UUID displayId = UUID.fromString(matcher.group(1));
                mapping.computeIfAbsent(displayId, ignored -> new ArrayList<>()).add(compiledNode.id());
            }
        }
        try {
            return objectMapper.writeValueAsString(new WorkflowDisplaySnapshot(draft,
                    mapping.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, entry -> List.copyOf(entry.getValue()),
                            (left, right) -> left, LinkedHashMap::new))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new WorkflowCompileException("Workflow 展示快照无法序列化");
        }
    }

    private WorkflowDisplaySnapshot readDisplay(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, WorkflowDisplaySnapshot.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Workflow 展示快照损坏", error);
        }
    }
}
