package io.testforge.casecatalog.suite.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.suite.entity.TestSuiteEntity;
import io.testforge.casecatalog.suite.model.CreateTestSuiteCommand;
import io.testforge.casecatalog.suite.model.TestSuiteView;
import io.testforge.casecatalog.suite.model.UpdateTestSuiteCommand;
import io.testforge.casecatalog.suite.repo.TestSuiteRepository;
import io.testforge.casecatalog.testcase.service.TestCaseCatalogException;
import io.testforge.casecatalog.testcase.service.TestCaseNotFoundException;
import io.testforge.casecatalog.testcase.service.TestCaseService;
import io.testforge.projectcatalog.service.ProjectCatalogNotFoundException;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

public class TestSuiteService {

    private static final int MAX_MEMBERS = 1_000;
    private static final int MAX_TAGS = 32;
    private static final int MAX_BINDINGS = 100;
    private static final int MAX_BINDING_JSON_BYTES = 65_535;

    private final TestSuiteRepository repository;
    private final ProjectCatalogService projectCatalogService;
    private final TestCaseService testCaseService;
    private final ObjectMapper objectMapper;

    public TestSuiteService(
            TestSuiteRepository repository,
            ProjectCatalogService projectCatalogService,
            TestCaseService testCaseService,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.projectCatalogService = projectCatalogService;
        this.testCaseService = testCaseService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TestSuiteView create(UUID projectId, CreateTestSuiteCommand command) {
        if (projectId == null) {
            throw new SuiteCatalogValidationException("projectId 不能为空");
        }
        Objects.requireNonNull(command, "command must not be null");
        requireTargetBelongsToProject(projectId, command.targetId());
        SuiteContent content = normalize(
                command.name(),
                command.caseIds(),
                command.tags(),
                command.parameterBindings()
        );
        validateMemberOwnership(projectId, command.targetId(), content.caseIds());

        var existing = repository.findByProjectIdAndTargetIdAndName(
                projectId,
                command.targetId(),
                content.name()
        );
        if (existing.isPresent()) {
            TestSuiteView view = toView(existing.get());
            if (sameContent(view, content)) {
                return view;
            }
            throw new SuiteCatalogConflictException("同一被测对象下已存在同名但内容不同的 TestSuite: " + content.name());
        }

        var entity = new TestSuiteEntity(
                UUID.randomUUID(),
                projectId,
                command.targetId(),
                content.name(),
                content.caseIds(),
                content.tags(),
                writeBindings(content.parameterBindings()),
                Instant.now()
        );
        try {
            return toView(repository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException exception) {
            throw new SuiteCatalogConflictException("同一被测对象下已存在同名 TestSuite: " + content.name());
        }
    }

    @Transactional
    public TestSuiteView update(UUID suiteId, UpdateTestSuiteCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        TestSuiteEntity entity = requireSuite(suiteId);
        if (command.expectedVersion() < 1) {
            throw new SuiteCatalogValidationException("expectedVersion 必须大于 0");
        }
        if (entity.getVersion() != command.expectedVersion()) {
            throw new SuiteCatalogConflictException(
                    "TestSuite 版本已变化，期望 " + command.expectedVersion() + "，实际 " + entity.getVersion()
            );
        }

        SuiteContent content = normalize(
                command.name(),
                command.caseIds(),
                command.tags(),
                command.parameterBindings()
        );
        validateMemberOwnership(entity.getProjectId(), entity.getTargetId(), content.caseIds());
        repository.findByProjectIdAndTargetIdAndName(entity.getProjectId(), entity.getTargetId(), content.name())
                .filter(existing -> !existing.getId().equals(entity.getId()))
                .ifPresent(existing -> {
                    throw new SuiteCatalogConflictException(
                            "同一被测对象下已存在同名 TestSuite: " + content.name()
                    );
                });

        entity.update(
                content.name(),
                content.caseIds(),
                content.tags(),
                writeBindings(content.parameterBindings()),
                Instant.now()
        );
        try {
            return toView(repository.saveAndFlush(entity));
        } catch (OptimisticLockingFailureException exception) {
            throw new SuiteCatalogConflictException("TestSuite 已被其他请求更新: " + suiteId);
        } catch (DataIntegrityViolationException exception) {
            throw new SuiteCatalogConflictException("同一被测对象下已存在同名 TestSuite: " + content.name());
        }
    }

    @Transactional(readOnly = true)
    public TestSuiteView get(UUID suiteId) {
        return toView(requireSuite(suiteId));
    }

    @Transactional(readOnly = true)
    public List<TestSuiteView> list(UUID projectId, UUID targetId) {
        if (projectId == null) {
            throw new SuiteCatalogValidationException("projectId 不能为空");
        }
        requireTargetBelongsToProject(projectId, targetId);
        return repository.findAllByProjectIdAndTargetIdOrderByNameAsc(projectId, targetId)
                .stream()
                .map(this::toView)
                .toList();
    }

    private TestSuiteEntity requireSuite(UUID suiteId) {
        if (suiteId == null) {
            throw new SuiteCatalogValidationException("suiteId 不能为空");
        }
        return repository.findById(suiteId)
                .orElseThrow(() -> new SuiteCatalogNotFoundException("TestSuite 不存在: " + suiteId));
    }

    private void requireTargetBelongsToProject(UUID projectId, UUID targetId) {
        if (targetId == null) {
            throw new SuiteCatalogValidationException("targetId 不能为空");
        }
        try {
            var target = projectCatalogService.requireTargetView(targetId);
            if (!projectId.equals(target.projectId())) {
                throw new SuiteCatalogValidationException("被测对象不属于指定项目");
            }
        } catch (ProjectCatalogNotFoundException exception) {
            throw new SuiteCatalogNotFoundException("被测对象不存在: " + targetId);
        }
    }

    private void validateMemberOwnership(UUID projectId, UUID targetId, Set<UUID> caseIds) {
        try {
            testCaseService.validateCaseReferences(projectId, targetId, caseIds);
        } catch (TestCaseNotFoundException exception) {
            throw new SuiteCatalogNotFoundException(exception.getMessage());
        } catch (TestCaseCatalogException exception) {
            throw new SuiteCatalogValidationException(exception.getMessage());
        }
    }

    private SuiteContent normalize(
            String name,
            List<UUID> caseIds,
            List<String> tags,
            Map<String, Object> parameterBindings
    ) {
        String normalizedName = normalizedName(name);
        Set<UUID> normalizedCaseIds = normalizedCaseIds(caseIds);
        Set<String> normalizedTags = normalizedTags(tags);
        Map<String, Object> normalizedBindings = normalizedBindings(parameterBindings);
        return new SuiteContent(normalizedName, normalizedCaseIds, normalizedTags, normalizedBindings);
    }

    private String normalizedName(String value) {
        if (value == null || value.isBlank()) {
            throw new SuiteCatalogValidationException("TestSuite 名称不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > 200) {
            throw new SuiteCatalogValidationException("TestSuite 名称不能超过 200 个字符");
        }
        return normalized;
    }

    private Set<UUID> normalizedCaseIds(List<UUID> values) {
        if (values == null || values.isEmpty()) {
            throw new SuiteCatalogValidationException("caseIds 至少包含一个 Case ID");
        }
        if (values.size() > MAX_MEMBERS) {
            throw new SuiteCatalogValidationException("caseIds 不能超过 " + MAX_MEMBERS + " 项");
        }
        var normalized = new TreeSet<UUID>();
        for (UUID value : values) {
            if (value == null) {
                throw new SuiteCatalogValidationException("caseIds 不能包含空值");
            }
            normalized.add(value);
        }
        return Collections.unmodifiableSet(normalized);
    }

    private Set<String> normalizedTags(List<String> values) {
        if (values == null) {
            throw new SuiteCatalogValidationException("tags 不能为空");
        }
        if (values.size() > MAX_TAGS) {
            throw new SuiteCatalogValidationException("tags 不能超过 " + MAX_TAGS + " 项");
        }
        var normalized = new TreeSet<String>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new SuiteCatalogValidationException("标签不能为空");
            }
            String tag = value.trim();
            if (tag.length() > 64) {
                throw new SuiteCatalogValidationException("标签不能超过 64 个字符");
            }
            normalized.add(tag);
        }
        return Collections.unmodifiableSet(normalized);
    }

    private Map<String, Object> normalizedBindings(Map<String, Object> values) {
        if (values == null) {
            throw new SuiteCatalogValidationException("parameterBindings 不能为空");
        }
        if (values.size() > MAX_BINDINGS) {
            throw new SuiteCatalogValidationException("parameterBindings 不能超过 " + MAX_BINDINGS + " 项");
        }
        var normalized = new TreeMap<String, Object>();
        values.forEach((rawKey, value) -> {
            if (rawKey == null || rawKey.isBlank() || value == null) {
                throw new SuiteCatalogValidationException("参数绑定的键和值不能为空");
            }
            String key = rawKey.trim();
            if (key.length() > 100) {
                throw new SuiteCatalogValidationException("参数绑定键不能超过 100 个字符");
            }
            if (normalized.putIfAbsent(key, canonicalValue(value)) != null) {
                throw new SuiteCatalogValidationException("参数绑定键规范化后重复: " + key);
            }
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
    }

    private Object canonicalValue(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BINDING_JSON_BYTES) {
                throw new SuiteCatalogValidationException("单个参数绑定值不能超过 65535 字节");
            }
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException exception) {
            throw new SuiteCatalogValidationException("参数绑定值必须可以序列化为 JSON");
        }
    }

    private Map<String, String> writeBindings(Map<String, Object> bindings) {
        var result = new LinkedHashMap<String, String>();
        bindings.forEach((key, value) -> {
            try {
                result.put(key, objectMapper.writeValueAsString(value));
            } catch (JsonProcessingException exception) {
                throw new SuiteCatalogValidationException("参数绑定值必须可以序列化为 JSON");
            }
        });
        return result;
    }

    private Map<String, Object> readBindings(Map<String, String> bindings) {
        var result = new TreeMap<String, Object>();
        bindings.forEach((key, json) -> {
            try {
                result.put(key, objectMapper.readValue(json, Object.class));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("TestSuite 参数绑定数据损坏: " + key, exception);
            }
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private TestSuiteView toView(TestSuiteEntity entity) {
        var caseIds = new ArrayList<>(entity.getCaseIds());
        Collections.sort(caseIds);
        var tags = new ArrayList<>(entity.getTags());
        Collections.sort(tags);
        return new TestSuiteView(
                entity.getId(),
                entity.getProjectId(),
                entity.getTargetId(),
                entity.getName(),
                List.copyOf(caseIds),
                List.copyOf(tags),
                readBindings(entity.getParameterBindingsJson()),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private boolean sameContent(TestSuiteView view, SuiteContent content) {
        return view.name().equals(content.name())
                && Set.copyOf(view.caseIds()).equals(content.caseIds())
                && Set.copyOf(view.tags()).equals(content.tags())
                && view.parameterBindings().equals(content.parameterBindings());
    }

    private record SuiteContent(
            String name,
            Set<UUID> caseIds,
            Set<String> tags,
            Map<String, Object> parameterBindings
    ) {
    }
}
