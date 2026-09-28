package io.testforge.casecatalog.testcase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.testcase.entity.TestCaseEntity;
import io.testforge.casecatalog.testcase.entity.TestScriptVersionEntity;
import io.testforge.casecatalog.testcase.model.CreateScriptVersionCommand;
import io.testforge.casecatalog.testcase.model.CreateTestCaseCommand;
import io.testforge.casecatalog.testcase.model.CaseExecutionView;
import io.testforge.casecatalog.testcase.model.CaseDefinition;
import io.testforge.casecatalog.testcase.model.CaseDefinitionView;
import io.testforge.casecatalog.testcase.model.ExecutionRequirement;
import io.testforge.casecatalog.testcase.model.LeaseScope;
import io.testforge.casecatalog.testcase.model.ScriptRunner;
import io.testforge.casecatalog.testcase.model.ScriptVersionView;
import io.testforge.casecatalog.testcase.model.TestCaseView;
import io.testforge.casecatalog.testcase.model.CaseScope;
import io.testforge.casecatalog.testcase.repo.TestCaseRepository;
import io.testforge.casecatalog.testcase.repo.TestScriptVersionRepository;
import io.testforge.projectcatalog.model.TargetView;
import io.testforge.projectcatalog.service.ProjectCatalogException;
import io.testforge.projectcatalog.service.ProjectCatalogNotFoundException;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public class TestCaseService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final TestCaseRepository testCaseRepository;
    private final TestScriptVersionRepository scriptVersionRepository;
    private final ProjectCatalogService projectCatalogService;
    private final ObjectMapper objectMapper;
    private final ParameterSchemaValidator schemaValidator;
    private final CaseDefinitionParser definitionParser;

    public TestCaseService(
            TestCaseRepository testCaseRepository,
            TestScriptVersionRepository scriptVersionRepository,
            ProjectCatalogService projectCatalogService,
            ObjectMapper objectMapper,
            CaseDefinitionParser definitionParser
    ) {
        this.testCaseRepository = testCaseRepository;
        this.scriptVersionRepository = scriptVersionRepository;
        this.projectCatalogService = projectCatalogService;
        this.objectMapper = objectMapper;
        this.schemaValidator = new ParameterSchemaValidator();
        this.definitionParser = definitionParser;
    }

    @Transactional(readOnly = true)
    public CaseDefinitionView validateDefinition(UUID projectId, String yaml) {
        requireProject(projectId);
        return definitionView(null, projectId, definitionParser.parse(projectId, yaml, false));
    }

    @Transactional
    public TestCaseView createDefinition(UUID projectId, String yaml) {
        UUID targetId = resolveTargetId(projectId, null);
        CaseDefinition definition = definitionParser.parse(projectId, yaml, true);
        if (testCaseRepository.findByProjectIdAndTargetIdAndName(projectId, targetId, definition.name()).isPresent()) {
            throw new TestCaseConflictException("被测对象下已存在同名用例: " + definition.name());
        }
        Instant now = Instant.now();
        TestCaseEntity entity = new TestCaseEntity(
                UUID.randomUUID(), projectId, targetId, CaseScope.PROJECT, definition.name(), definition.kind(),
                writeSchema(definition.parameters()), definition.timeoutSeconds(),
                writeExecutionRequirement(definition.executionRequirement()), definition.tags(), now
        );
        applyDefinition(entity, definition, now);
        try {
            return toView(testCaseRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException exception) {
            throw new TestCaseConflictException("被测对象下已存在同名用例: " + definition.name());
        }
    }

    @Transactional
    public TestCaseView updateDefinition(UUID caseId, String yaml) {
        TestCaseEntity entity = testCaseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new TestCaseNotFoundException("用例不存在: " + caseId));
        CaseDefinition definition = definitionParser.parse(entity.getProjectId(), yaml, true);
        testCaseRepository.findByProjectIdAndTargetIdAndName(
                        entity.getProjectId(), entity.getTargetId(), definition.name()
                )
                .filter(other -> !other.getId().equals(caseId))
                .ifPresent(other -> { throw new TestCaseConflictException("被测对象下已存在同名用例: " + definition.name()); });
        applyDefinition(entity, definition, Instant.now());
        return toView(testCaseRepository.saveAndFlush(entity));
    }

    @Transactional(readOnly = true)
    public CaseDefinitionView getDefinition(UUID caseId) {
        TestCaseEntity entity = requireTestCase(caseId);
        if (!entity.hasDefinition()) {
            throw new TestCaseConflictException("该 Case 仍使用旧 ScriptVersion，请先转换为 YAML");
        }
        CaseDefinition definition = definitionParser.parse(entity.getProjectId(), entity.getDefinitionYaml(), false);
        return new CaseDefinitionView(
                entity.getId(), entity.getProjectId(), true, definition.yaml(), definition.digest(),
                definition.name(), definition.kind(), definition.tags(), definition.parameters(),
                definition.timeoutSeconds(), definition.executionRequirement(), entity.getScriptAssetId(),
                "testforge://assets/" + entity.getScriptAssetId() + "#" + entity.getScriptEntrypoint(),
                entity.getScriptEntrypoint(), entity.getScriptChecksum()
        );
    }

    @Transactional
    public TestCaseView createTestCase(UUID projectId, CreateTestCaseCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        UUID targetId = resolveTargetId(projectId, command.targetId());
        String name = normalizeName(command.name());
        if (command.kind() == null) {
            throw new TestCaseValidationException("用例 kind 不能为空");
        }
        int timeoutSeconds = normalizeTimeout(command.timeoutSeconds());
        Set<String> tags = normalizeTags(command.tags());
        Map<String, Object> schema = normalizeSchema(command.parameterSchema());
        schemaValidator.validateSchema(schema);
        ExecutionRequirement executionRequirement = normalizeExecutionRequirement(command.executionRequirement());

        if (testCaseRepository.findByProjectIdAndTargetIdAndName(projectId, targetId, name).isPresent()) {
            throw new TestCaseConflictException("被测对象下已存在同名用例: " + name);
        }

        Instant now = Instant.now();
        TestCaseEntity entity = new TestCaseEntity(
                UUID.randomUUID(),
                projectId,
                targetId,
                command.scope() == null ? CaseScope.PROJECT : command.scope(),
                name,
                command.kind(),
                writeSchema(schema),
                timeoutSeconds,
                writeExecutionRequirement(executionRequirement),
                tags,
                now
        );
        try {
            return toView(testCaseRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException exception) {
            throw new TestCaseConflictException("被测对象下已存在同名用例: " + name);
        }
    }

    @Transactional
    public ScriptVersionView createScriptVersion(UUID caseId, CreateScriptVersionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (caseId == null) {
            throw new TestCaseValidationException("caseId 不能为空");
        }
        TestCaseEntity testCase = testCaseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new TestCaseNotFoundException("用例不存在: " + caseId));
        if (testCase.hasDefinition()) {
            throw new TestCaseConflictException("YAML Case 不支持创建 ScriptVersion，请直接修改 YAML 或 Asset 引用");
        }
        if (command.runner() == null) {
            throw new TestCaseValidationException("runner 不能为空");
        }
        requireMatchingExecutor(
                readExecutionRequirement(testCase.getExecutionRequirementJson(), command.runner()),
                command.runner()
        );
        String sourceRef = normalizeSourceRef(command.sourceRef());
        String checksum = normalizeChecksum(command.checksum());
        int nextVersion = scriptVersionRepository.findTopByCaseIdOrderByVersionDesc(caseId)
                .map(version -> version.getVersion() + 1)
                .orElse(1);
        Instant now = Instant.now();
        TestScriptVersionEntity version = new TestScriptVersionEntity(
                UUID.randomUUID(),
                caseId,
                command.runner(),
                sourceRef,
                checksum,
                nextVersion,
                now
        );
        try {
            TestScriptVersionEntity saved = scriptVersionRepository.saveAndFlush(version);
            testCase.attachScriptVersion(saved.getId(), now);
            testCaseRepository.save(testCase);
            return toView(saved);
        } catch (DataIntegrityViolationException exception) {
            throw new TestCaseConflictException("脚本版本并发创建冲突，请重试");
        }
    }

    @Transactional
    public TestCaseView updateTestCase(UUID caseId, CreateTestCaseCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        TestCaseEntity testCase = testCaseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new TestCaseNotFoundException("用例不存在: " + caseId));
        if (testCase.hasDefinition()) {
            throw new TestCaseConflictException("YAML Case 只能通过 Definition API 修改");
        }
        UUID targetId = command.targetId() == null ? testCase.getTargetId() : command.targetId();
        if (!testCase.getTargetId().equals(targetId)) {
            throw new TestCaseValidationException("编辑用例不能改变 targetId");
        }
        String name = normalizeName(command.name());
        if (command.kind() == null) {
            throw new TestCaseValidationException("用例 kind 不能为空");
        }
        int timeoutSeconds = normalizeTimeout(command.timeoutSeconds());
        Set<String> tags = normalizeTags(command.tags());
        Map<String, Object> schema = normalizeSchema(command.parameterSchema());
        schemaValidator.validateSchema(schema);
        ExecutionRequirement executionRequirement = command.executionRequirement() == null
                ? readExecutionRequirement(testCase.getExecutionRequirementJson(), currentRunner(testCase))
                : normalizeExecutionRequirement(command.executionRequirement());
        ScriptRunner attachedRunner = currentRunner(testCase);
        if (attachedRunner != null) {
            requireMatchingExecutor(executionRequirement, attachedRunner);
        }
        testCaseRepository.findByProjectIdAndTargetIdAndName(
                        testCase.getProjectId(), testCase.getTargetId(), name
                )
                .filter(other -> !other.getId().equals(caseId))
                .ifPresent(other -> { throw new TestCaseConflictException("被测对象下已存在同名用例: " + name); });
        testCase.update(name, command.kind(), command.scope() == null ? testCase.getScope() : command.scope(),
                writeSchema(schema), timeoutSeconds, writeExecutionRequirement(executionRequirement),
                tags, Instant.now());
        return toView(testCaseRepository.saveAndFlush(testCase));
    }

    @Transactional(readOnly = true)
    public TestCaseView getTestCase(UUID caseId) {
        return toView(requireTestCase(caseId));
    }

    @Transactional(readOnly = true)
    public List<TestCaseView> listTestCases(UUID projectId) {
        requireProject(projectId);
        return testCaseRepository.findAllByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TestCaseView> listSharedTestCases() {
        return testCaseRepository.findAllByScopeOrderByCreatedAtAsc(CaseScope.SHARED).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ScriptVersionView> listScriptVersions(UUID caseId) {
        requireTestCase(caseId);
        return scriptVersionRepository.findAllByCaseIdOrderByVersionAsc(caseId).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 解析 Workflow 节点固定引用的脚本版本。该公开只读视图避免 Workflow 跨子域访问 Repo。
     */
    @Transactional(readOnly = true)
    public CaseExecutionView requireExecutionVersion(UUID caseId, int scriptVersion) {
        if (scriptVersion < 1) {
            throw new TestCaseValidationException("脚本版本必须大于 0");
        }
        TestCaseEntity testCase = requireTestCase(caseId);
        if (testCase.hasDefinition()) {
            if (scriptVersion != 1) throw new TestCaseNotFoundException("YAML Case 不存在脚本版本: " + scriptVersion);
            return toDefinitionExecutionView(testCase);
        }
        TestScriptVersionEntity version = scriptVersionRepository.findByCaseIdAndVersion(caseId, scriptVersion)
                .orElseThrow(() -> new TestCaseNotFoundException(
                        "用例脚本版本不存在: " + caseId + "@" + scriptVersion
                ));
        return toExecutionView(testCase, version);
    }

    /**
     * Suite 发布时锁定成员 Case 当时的最新脚本版本。
     */
    @Transactional(readOnly = true)
    public CaseExecutionView requireLatestExecutionVersion(UUID caseId) {
        TestCaseEntity testCase = requireTestCase(caseId);
        if (testCase.hasDefinition()) return toDefinitionExecutionView(testCase);
        TestScriptVersionEntity version = scriptVersionRepository.findTopByCaseIdOrderByVersionDesc(caseId)
                .orElseThrow(() -> new TestCaseNotFoundException("用例尚未创建脚本版本: " + caseId));
        return toExecutionView(testCase, version);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateParameters(UUID caseId, Map<String, Object> parameters) {
        TestCaseEntity testCase = requireTestCase(caseId);
        Map<String, Object> schema = readSchema(testCase.getParameterSchemaJson());
        schemaValidator.validateParameters(schema, parameters);
        return immutableMap(parameters);
    }

    /**
     * 供 Suite、Workflow 等同模块资产通过公开 Service 校验 Case 引用，避免跨包访问仓储。
     */
    @Transactional(readOnly = true)
    public void validateCaseReferences(UUID projectId, UUID targetId, Collection<UUID> caseIds) {
        if (projectId == null || targetId == null) {
            throw new TestCaseValidationException("projectId 和 targetId 不能为空");
        }
        if (caseIds == null || caseIds.isEmpty()) {
            throw new TestCaseValidationException("caseIds 至少包含一个 Case ID");
        }

        Set<UUID> normalizedCaseIds = new TreeSet<>();
        for (UUID caseId : caseIds) {
            if (caseId == null) {
                throw new TestCaseValidationException("caseIds 不能包含空值");
            }
            normalizedCaseIds.add(caseId);
        }

        Map<UUID, TestCaseEntity> casesById = new HashMap<>();
        testCaseRepository.findAllById(normalizedCaseIds)
                .forEach(testCase -> casesById.put(testCase.getId(), testCase));
        for (UUID caseId : normalizedCaseIds) {
            TestCaseEntity testCase = casesById.get(caseId);
            if (testCase == null) {
                throw new TestCaseNotFoundException("用例不存在: " + caseId);
            }
            if (!projectId.equals(testCase.getProjectId()) || !targetId.equals(testCase.getTargetId())) {
                throw new TestCaseValidationException("用例不属于指定项目与被测对象: " + caseId);
            }
        }
    }

    private TestCaseEntity requireTestCase(UUID caseId) {
        if (caseId == null) {
            throw new TestCaseValidationException("caseId 不能为空");
        }
        return testCaseRepository.findById(caseId)
                .orElseThrow(() -> new TestCaseNotFoundException("用例不存在: " + caseId));
    }

    private UUID resolveTargetId(UUID projectId, UUID targetId) {
        requireProject(projectId);
        if (targetId == null) {
            try {
                return projectCatalogService.requireExecutionTarget(projectId).id();
            } catch (ProjectCatalogNotFoundException exception) {
                throw new TestCaseNotFoundException(exception.getMessage());
            } catch (ProjectCatalogException exception) {
                throw new TestCaseValidationException(exception.getMessage());
            }
        }
        try {
            TargetView target = projectCatalogService.requireTargetView(targetId);
            if (!target.projectId().equals(projectId)) {
                throw new TestCaseValidationException("targetId 不属于路径中的 projectId");
            }
            return target.id();
        } catch (ProjectCatalogNotFoundException exception) {
            throw new TestCaseNotFoundException(exception.getMessage());
        } catch (ProjectCatalogException exception) {
            throw new TestCaseValidationException(exception.getMessage());
        }
    }

    private void requireProject(UUID projectId) {
        if (projectId == null) {
            throw new TestCaseValidationException("projectId 不能为空");
        }
        try {
            projectCatalogService.requireProjectView(projectId);
        } catch (ProjectCatalogNotFoundException exception) {
            throw new TestCaseNotFoundException(exception.getMessage());
        } catch (ProjectCatalogException exception) {
            throw new TestCaseValidationException(exception.getMessage());
        }
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new TestCaseValidationException("用例名称不能为空");
        }
        String normalized = name.trim();
        if (normalized.length() > 200) {
            throw new TestCaseValidationException("用例名称不能超过 200 个字符");
        }
        return normalized;
    }

    private int normalizeTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds < 1 || timeoutSeconds > 86_400) {
            throw new TestCaseValidationException("timeoutSeconds 必须在 1 到 86400 之间");
        }
        return timeoutSeconds;
    }

    private Set<String> normalizeTags(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        if (values.size() > 50) {
            throw new TestCaseValidationException("标签数量不能超过 50 个");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new TestCaseValidationException("标签不能为空");
            }
            String tag = value.trim();
            if (tag.length() > 64) {
                throw new TestCaseValidationException("标签不能超过 64 个字符: " + tag);
            }
            if (!normalized.add(tag)) {
                throw new TestCaseValidationException("标签去除首尾空格后不能重复: " + tag);
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private Map<String, Object> normalizeSchema(Map<String, Object> schema) {
        if (schema == null) {
            throw new TestCaseValidationException("parameters 参数 Schema 不能为空");
        }
        try {
            return objectMapper.readValue(objectMapper.writeValueAsBytes(schema), MAP_TYPE);
        } catch (IOException exception) {
            throw new TestCaseValidationException("parameters 参数 Schema 必须可以序列化为 JSON");
        }
    }

    private String normalizeSourceRef(String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            throw new TestCaseValidationException("sourceRef 不能为空");
        }
        String normalized = sourceRef.trim();
        if (normalized.length() > 1024) {
            throw new TestCaseValidationException("sourceRef 不能超过 1024 个字符");
        }
        return normalized;
    }

    private String normalizeChecksum(String checksum) {
        if (checksum == null || !checksum.matches("^sha256:[a-f0-9]{64}$")) {
            throw new TestCaseValidationException("checksum 必须匹配 sha256:[a-f0-9]{64}");
        }
        return checksum;
    }

    private String writeSchema(Map<String, Object> schema) {
        try {
            return objectMapper.writeValueAsString(schema);
        } catch (JsonProcessingException exception) {
            throw new TestCaseValidationException("parameters 参数 Schema 无法序列化");
        }
    }

    private ExecutionRequirement normalizeExecutionRequirement(ExecutionRequirement value) {
        if (value == null) {
            return null;
        }
        if (value.executorType() == null || value.executorType().isBlank()
                || value.executorType().trim().length() > 64) {
            throw new TestCaseValidationException("executionRequirement.executorType 必须是长度不超过 64 的非空字符串");
        }
        if (value.interaction() == null) {
            throw new TestCaseValidationException("executionRequirement.interaction 不能为空");
        }
        if (value.resourceProfile() == null || value.resourceProfile().isBlank()
                || value.resourceProfile().trim().length() > 64) {
            throw new TestCaseValidationException("executionRequirement.resourceProfile 必须是长度不超过 64 的非空字符串");
        }
        if (value.capabilities().size() > 32 || value.capabilities().stream()
                .anyMatch(item -> item == null || item.isBlank() || item.length() > 64)) {
            throw new TestCaseValidationException("executionRequirement.capabilities 最多 32 项且每项不超过 64 字符");
        }
        if (value.leaseScope() == LeaseScope.SUBFLOW
                && (value.sessionKey() == null || value.sessionKey().isBlank())) {
            throw new TestCaseValidationException("SUBFLOW 资源租约必须声明 sessionKey");
        }
        if (value.leaseScope() == LeaseScope.CASE && value.sessionKey() != null) {
            throw new TestCaseValidationException("CASE 资源租约不能声明 sessionKey");
        }
        if ("airtest".equalsIgnoreCase(value.executorType())
                && value.interaction() != io.testforge.casecatalog.testcase.model.InteractionMode.UI) {
            throw new TestCaseValidationException("当前 Airtest Adapter 只支持 UI 交互资源");
        }
        if ("pytest-http".equalsIgnoreCase(value.executorType())
                && value.interaction() != io.testforge.casecatalog.testcase.model.InteractionMode.HEADLESS) {
            throw new TestCaseValidationException("当前 pytest-http Adapter 只支持 HEADLESS 计算资源");
        }
        if ("playwright-web".equalsIgnoreCase(value.executorType())
                && value.interaction() != io.testforge.casecatalog.testcase.model.InteractionMode.HEADLESS) {
            throw new TestCaseValidationException("当前 playwright-web Adapter 使用可并发的 HEADLESS 浏览器计算资源");
        }
        return new ExecutionRequirement(
                value.executorType().trim().toLowerCase(java.util.Locale.ROOT),
                value.interaction(),
                value.capabilities().stream()
                        .map(item -> item.trim().toUpperCase(java.util.Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet()),
                value.resourceProfile().trim(),
                value.leaseScope(),
                value.sessionKey()
        );
    }

    private String writeExecutionRequirement(ExecutionRequirement value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new TestCaseValidationException("executionRequirement 无法序列化");
        }
    }

    private ExecutionRequirement readExecutionRequirement(String json, ScriptRunner fallbackRunner) {
        if (json == null || json.isBlank()) {
            return ExecutionRequirement.legacyDefault(fallbackRunner);
        }
        try {
            return normalizeExecutionRequirement(objectMapper.readValue(json, ExecutionRequirement.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Case executionRequirement 数据损坏", exception);
        }
    }

    private ScriptRunner currentRunner(TestCaseEntity testCase) {
        if (testCase.getScriptVersionId() == null) {
            return null;
        }
        return scriptVersionRepository.findById(testCase.getScriptVersionId())
                .map(TestScriptVersionEntity::getRunner)
                .orElse(null);
    }

    private void requireMatchingExecutor(ExecutionRequirement requirement, ScriptRunner runner) {
        if (requirement != null && !runner.contractValue().equalsIgnoreCase(requirement.executorType())) {
            throw new TestCaseValidationException(
                    "executionRequirement.executorType 必须与脚本 runner 一致: " + runner.contractValue()
            );
        }
    }

    private Map<String, Object> readSchema(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("用例参数 Schema 数据损坏", exception);
        }
    }

    private TestCaseView toView(TestCaseEntity entity) {
        return new TestCaseView(
                entity.getId(),
                entity.getProjectId(),
                entity.getTargetId(),
                entity.getName(),
                entity.getKind(),
                entity.getScope(),
                entity.getTags(),
                immutableMap(readSchema(entity.getParameterSchemaJson())),
                entity.getTimeoutSeconds(),
                entity.getScriptVersionId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                readExecutionRequirement(entity.getExecutionRequirementJson(), currentRunner(entity)),
                entity.hasDefinition(),
                entity.getDefinitionDigest()
        );
    }

    private void applyDefinition(TestCaseEntity entity, CaseDefinition definition, Instant changedAt) {
        entity.applyDefinition(
                definition.yaml(), definition.digest(), definition.script().assetId(),
                definition.script().entrypoint(), definition.script().checksum(), definition.name(),
                definition.kind(), writeSchema(definition.parameters()), definition.timeoutSeconds(),
                writeExecutionRequirement(definition.executionRequirement()), definition.tags(), changedAt
        );
    }

    private CaseDefinitionView definitionView(UUID caseId, UUID projectId, CaseDefinition definition) {
        return new CaseDefinitionView(
                caseId, projectId, true, definition.yaml(), definition.digest(), definition.name(),
                definition.kind(), definition.tags(), definition.parameters(), definition.timeoutSeconds(),
                definition.executionRequirement(), definition.script().assetId(), definition.script().sourceRef(),
                definition.script().entrypoint(), definition.script().checksum()
        );
    }

    private ScriptVersionView toView(TestScriptVersionEntity entity) {
        return new ScriptVersionView(
                entity.getId(),
                entity.getCaseId(),
                entity.getRunner(),
                entity.getSourceRef(),
                entity.getChecksum(),
                entity.getVersion(),
                entity.getCreatedAt()
        );
    }

    private CaseExecutionView toExecutionView(
            TestCaseEntity testCase,
            TestScriptVersionEntity version
    ) {
        return new CaseExecutionView(
                testCase.getId(),
                testCase.getName(),
                testCase.getProjectId(),
                testCase.getTargetId(),
                testCase.getKind(),
                testCase.getScope(),
                testCase.getTimeoutSeconds(),
                version.getId(),
                version.getVersion(),
                version.getRunner(),
                version.getSourceRef(),
                version.getChecksum(),
                readExecutionRequirement(testCase.getExecutionRequirementJson(), version.getRunner())
        );
    }

    private CaseExecutionView toDefinitionExecutionView(TestCaseEntity testCase) {
        ExecutionRequirement requirement = readExecutionRequirement(testCase.getExecutionRequirementJson(), null);
        if (testCase.getScriptAssetId() == null || testCase.getScriptChecksum() == null
                || testCase.getScriptEntrypoint() == null) {
            throw new IllegalStateException("YAML Case 缺少已物化脚本资源: " + testCase.getId());
        }
        return new CaseExecutionView(
                testCase.getId(), testCase.getName(), testCase.getProjectId(), testCase.getTargetId(), testCase.getKind(),
                testCase.getScope(), testCase.getTimeoutSeconds(), testCase.getScriptAssetId(), 1,
                ScriptRunner.fromContractValue(requirement.executorType()),
                "testforge://assets/" + testCase.getScriptAssetId() + "#" + testCase.getScriptEntrypoint(),
                testCase.getScriptChecksum(), requirement
        );
    }

    private Map<String, Object> immutableMap(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
