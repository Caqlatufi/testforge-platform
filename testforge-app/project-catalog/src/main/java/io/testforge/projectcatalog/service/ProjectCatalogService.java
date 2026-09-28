package io.testforge.projectcatalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.projectcatalog.entity.TestEnvironmentEntity;
import io.testforge.projectcatalog.entity.TestProjectEntity;
import io.testforge.projectcatalog.entity.TestTargetEntity;
import io.testforge.projectcatalog.model.CreateEnvironmentCommand;
import io.testforge.projectcatalog.model.CreateProjectCommand;
import io.testforge.projectcatalog.model.CreateTargetCommand;
import io.testforge.projectcatalog.model.EnvironmentView;
import io.testforge.projectcatalog.model.EnvironmentPlatform;
import io.testforge.projectcatalog.model.ProjectState;
import io.testforge.projectcatalog.model.ProjectView;
import io.testforge.projectcatalog.model.TargetView;
import io.testforge.projectcatalog.repo.ProjectEnvironmentRepository;
import io.testforge.projectcatalog.repo.ProjectRepository;
import io.testforge.projectcatalog.repo.ProjectTargetRepository;
import io.testforge.projectcatalog.revision.GitRevisionResolver;
import io.testforge.projectcatalog.revision.ResolvedRevision;
import io.testforge.projectcatalog.revision.RevisionResolver;
import io.testforge.projectcatalog.revision.RevisionSelector;
import io.testforge.projectcatalog.revision.RevisionOption;
import io.testforge.projectcatalog.revision.RevisionType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class ProjectCatalogService {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> SENSITIVE_CONFIG_KEYS = Set.of(
            "password", "passwd", "secret", "token", "apikey", "credential", "privatekey"
    );

    private final ProjectRepository projectRepository;
    private final ProjectTargetRepository targetRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final ObjectMapper objectMapper;
    private final RevisionResolver revisionResolver;

    public ProjectCatalogService(
            ProjectRepository projectRepository,
            ProjectTargetRepository targetRepository,
            ProjectEnvironmentRepository environmentRepository,
            ObjectMapper objectMapper
    ) {
        this(projectRepository, targetRepository, environmentRepository, objectMapper, new GitRevisionResolver());
    }

    public ProjectCatalogService(
            ProjectRepository projectRepository,
            ProjectTargetRepository targetRepository,
            ProjectEnvironmentRepository environmentRepository,
            ObjectMapper objectMapper,
            RevisionResolver revisionResolver
    ) {
        this.projectRepository = projectRepository;
        this.targetRepository = targetRepository;
        this.environmentRepository = environmentRepository;
        this.objectMapper = objectMapper;
        this.revisionResolver = revisionResolver;
    }

    @Transactional
    public ProjectView createProject(CreateProjectCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String name = normalizedName(command.name(), "项目名称");
        UUID projectId = UUID.randomUUID();
        String code = command.code() == null || command.code().isBlank()
                ? projectId.toString()
                : normalizedCode(command.code());
        SourceControl sourceControl = normalizedSourceControl(command.repositoryUrl(), command.defaultBranch());
        var targetType = command.targetType() == null ? io.testforge.projectcatalog.model.TargetType.HTTP_SERVICE
                : command.targetType();

        var existing = projectRepository.findByCode(code);
        if (existing.isPresent()) {
            if (existing.get().getName().equals(name)) {
                return toProjectView(existing.get(), List.of());
            }
            throw new ProjectCatalogConflictException("项目编码已存在: " + code);
        }

        var entity = new TestProjectEntity(projectId, name, code, ProjectState.ACTIVE, targetType,
                sourceControl.repositoryUrl(), sourceControl.defaultBranch(), null, null,
                null, null, Instant.now());
        try {
            TestProjectEntity saved = projectRepository.saveAndFlush(entity);
            if (sourceControl.repositoryUrl() != null) {
                targetRepository.saveAndFlush(new TestTargetEntity(UUID.randomUUID(), saved.getId(), name,
                        targetType, sourceControl.repositoryUrl(), sourceControl.defaultBranch(), Instant.now()));
            }
            return getProject(saved.getId());
        } catch (DataIntegrityViolationException exception) {
            throw new ProjectCatalogConflictException("项目编码已存在: " + code);
        }
    }

    @Transactional
    public TargetView createTarget(UUID projectId, CreateTargetCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        requireProject(projectId);
        String name = normalizedName(command.name(), "被测对象名称");
        if (command.type() == null) {
            throw new ProjectCatalogValidationException("被测对象类型不能为空");
        }
        SourceControl sourceControl = normalizedSourceControl(command.repositoryUrl(), command.defaultBranch());

        var existing = targetRepository.findByProjectIdAndName(projectId, name);
        if (existing.isPresent()) {
            if (existing.get().getType() == command.type()
                    && Objects.equals(existing.get().getRepositoryUrl(), sourceControl.repositoryUrl())
                    && Objects.equals(existing.get().getDefaultBranch(), sourceControl.defaultBranch())) {
                return toTargetView(existing.get(), List.of());
            }
            throw new ProjectCatalogConflictException("项目下已存在同名但配置不同的被测对象: " + name);
        }

        var entity = new TestTargetEntity(
                UUID.randomUUID(),
                projectId,
                name,
                command.type(),
                sourceControl.repositoryUrl(),
                sourceControl.defaultBranch(),
                Instant.now()
        );
        try {
            return toTargetView(targetRepository.saveAndFlush(entity), List.of());
        } catch (DataIntegrityViolationException exception) {
            throw new ProjectCatalogConflictException("项目下已存在同名被测对象: " + name);
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectView> listProjects() {
        return projectRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(project -> toProjectView(project, List.of()))
                .toList();
    }

    @Transactional
    public ProjectView updateProject(UUID projectId, CreateProjectCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        TestProjectEntity project = requireProject(projectId);
        String name = normalizedName(command.name(), "项目名称");
        String code = command.code() == null || command.code().isBlank()
                ? project.getCode()
                : normalizedCode(command.code());
        SourceControl sourceControl = normalizedSourceControl(command.repositoryUrl(), command.defaultBranch());
        var targetType = command.targetType() == null
                ? (project.getTargetType() == null ? io.testforge.projectcatalog.model.TargetType.HTTP_SERVICE : project.getTargetType())
                : command.targetType();
        projectRepository.findByCode(code)
                .filter(other -> !other.getId().equals(projectId))
                .ifPresent(other -> { throw new ProjectCatalogConflictException("项目编码已存在: " + code); });
        project.update(name, code, targetType, sourceControl.repositoryUrl(), sourceControl.defaultBranch(),
                null, null, null, null, Instant.now());
        projectRepository.saveAndFlush(project);
        if (sourceControl.repositoryUrl() != null) {
            List<TestTargetEntity> targets = targetRepository.findAllByProjectIdOrderByCreatedAtAsc(projectId);
            if (targets.isEmpty()) {
                targetRepository.saveAndFlush(new TestTargetEntity(UUID.randomUUID(), projectId, name, targetType,
                        sourceControl.repositoryUrl(), sourceControl.defaultBranch(), Instant.now()));
            } else {
                TestTargetEntity primary = targets.getFirst();
                primary.update(name, targetType, sourceControl.repositoryUrl(), sourceControl.defaultBranch());
                targetRepository.saveAndFlush(primary);
            }
        }
        return getProject(projectId);
    }

    @Transactional
    public TargetView updateTarget(UUID targetId, CreateTargetCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        TestTargetEntity target = requireTarget(targetId);
        String name = normalizedName(command.name(), "被测对象名称");
        if (command.type() == null) {
            throw new ProjectCatalogValidationException("被测对象类型不能为空");
        }
        SourceControl sourceControl = normalizedSourceControl(command.repositoryUrl(), command.defaultBranch());
        targetRepository.findByProjectIdAndName(target.getProjectId(), name)
                .filter(other -> !other.getId().equals(targetId))
                .ifPresent(other -> { throw new ProjectCatalogConflictException("项目下已存在同名被测对象: " + name); });
        target.update(name, command.type(), sourceControl.repositoryUrl(), sourceControl.defaultBranch());
        targetRepository.saveAndFlush(target);
        return requireTargetView(targetId);
    }

    @Transactional(readOnly = true)
    public ResolvedRevision resolveRevision(UUID targetId, RevisionSelector selector) {
        TestTargetEntity target = requireTarget(targetId);
        return revisionResolver.resolve(target.getRepositoryUrl(), target.getDefaultBranch(), selector);
    }

    @Transactional(readOnly = true)
    public ResolvedRevision resolveProjectRevision(UUID projectId, RevisionSelector selector) {
        TestProjectEntity project = requireProject(projectId);
        if (project.getRepositoryUrl() != null && project.getDefaultBranch() != null) {
            return revisionResolver.resolve(project.getRepositoryUrl(), project.getDefaultBranch(), selector);
        }
        TargetView target = requireExecutionTarget(projectId);
        return revisionResolver.resolve(target.repositoryUrl(), target.defaultBranch(), selector);
    }

    @Transactional(readOnly = true)
    public List<RevisionOption> listProjectRevisions(UUID projectId, RevisionType type) {
        TestProjectEntity project = requireProject(projectId);
        if (project.getRepositoryUrl() != null && project.getDefaultBranch() != null) {
            return revisionResolver.list(project.getRepositoryUrl(), project.getDefaultBranch(), type);
        }
        TargetView target = requireExecutionTarget(projectId);
        return revisionResolver.list(target.repositoryUrl(), target.defaultBranch(), type);
    }

    @Transactional(readOnly = true)
    public TargetView requireExecutionTarget(UUID projectId) {
        requireProject(projectId);
        return targetRepository.findAllByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .findFirst()
                .map(target -> toTargetView(target, List.of()))
                .orElseThrow(() -> new ProjectCatalogValidationException(
                        "项目尚未配置代码仓库，无法建立执行目标"));
    }

    @Transactional(readOnly = true)
    public List<EnvironmentView> listEnvironments(Boolean enabled, EnvironmentPlatform platform) {
        return environmentRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(this::toEnvironmentView)
                .filter(environment -> enabled == null || environment.enabled() == enabled)
                .filter(environment -> platform == null || environment.platform() == platform)
                .toList();
    }

    @Transactional(readOnly = true)
    public EnvironmentView requireEnvironmentView(UUID environmentId) {
        if (environmentId == null) throw new ProjectCatalogValidationException("environmentId 不能为空");
        return environmentRepository.findById(environmentId)
                .map(this::toEnvironmentView)
                .orElseThrow(() -> new ProjectCatalogNotFoundException("环境不存在: " + environmentId));
    }

    @Transactional
    public EnvironmentView updateEnvironment(UUID environmentId, CreateEnvironmentCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        TestEnvironmentEntity environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ProjectCatalogNotFoundException("环境不存在: " + environmentId));
        String name = normalizedName(command.name(), "环境名称");
        String endpoint = normalizedEndpoint(command.endpoint());
        String providerKey = normalizedProviderEnvironmentKey(command.providerEnvironmentKey());
        EnvironmentPlatform platform = normalizedPlatform(command.platform());
        String resourcePoolKey = normalizedResourcePoolKey(command.resourcePoolKey());
        int capacity = 1;
        Map<String, Object> config = immutableObjectMap(command.config(), "config");
        Map<String, String> secretRefs = immutableStringMap(command.secretRefs());
        rejectSensitiveConfig(config);
        environmentRepository.findByName(name)
                .filter(other -> !other.getId().equals(environmentId))
                .ifPresent(other -> { throw new ProjectCatalogConflictException("已存在同名全局环境: " + name); });
        environmentRepository.findByProviderEnvironmentKey(providerKey)
                .filter(other -> !other.getId().equals(environmentId))
                .ifPresent(other -> { throw new ProjectCatalogConflictException("环境发布键已存在: " + providerKey); });
        environment.update(name, endpoint, providerKey, platform, resourcePoolKey, capacity, command.enabled(),
                command.initializeOnNextDeploy(), writeJson(config), writeJson(secretRefs));
        return toEnvironmentView(environmentRepository.saveAndFlush(environment));
    }

    @Transactional
    public EnvironmentView createEnvironment(CreateEnvironmentCommand command) {
        return createEnvironment(null, null, command);
    }

    @Transactional
    public EnvironmentView createEnvironment(UUID targetId, CreateEnvironmentCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        TestTargetEntity target = requireTarget(targetId);
        return createEnvironment(target.getProjectId(), targetId, command);
    }

    private EnvironmentView createEnvironment(UUID projectId, UUID targetId, CreateEnvironmentCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String name = normalizedName(command.name(), "环境名称");
        String endpoint = normalizedEndpoint(command.endpoint());
        String providerKey = normalizedProviderEnvironmentKey(command.providerEnvironmentKey());
        EnvironmentPlatform platform = normalizedPlatform(command.platform());
        String resourcePoolKey = normalizedResourcePoolKey(command.resourcePoolKey());
        int capacity = 1;
        Map<String, Object> config = immutableObjectMap(command.config(), "config");
        Map<String, String> secretRefs = immutableStringMap(command.secretRefs());
        rejectSensitiveConfig(config);

        var existing = environmentRepository.findByName(name);
        if (existing.isPresent()) {
            EnvironmentView view = toEnvironmentView(existing.get());
            if (Objects.equals(view.endpoint(), endpoint)
                    && view.providerEnvironmentKey().equals(providerKey)
                    && view.platform() == platform
                    && view.resourcePoolKey().equals(resourcePoolKey)
                    && view.capacity() == capacity
                    && view.enabled() == command.enabled()
                    && view.initializeOnNextDeploy() == command.initializeOnNextDeploy()
                    && view.config().equals(config)
                    && view.secretRefs().equals(secretRefs)) {
                return view;
            }
            throw new ProjectCatalogConflictException("已存在同名但配置不同的全局环境: " + name);
        }
        environmentRepository.findByProviderEnvironmentKey(providerKey).ifPresent(other -> {
            throw new ProjectCatalogConflictException("环境发布键已存在: " + providerKey);
        });

        var entity = new TestEnvironmentEntity(
                UUID.randomUUID(),
                projectId,
                targetId,
                name,
                endpoint,
                providerKey,
                platform,
                resourcePoolKey,
                capacity,
                command.enabled(),
                command.initializeOnNextDeploy(),
                writeJson(config),
                writeJson(secretRefs),
                Instant.now()
        );
        try {
            return toEnvironmentView(environmentRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException exception) {
            throw new ProjectCatalogConflictException("已存在同名全局环境: " + name);
        }
    }

    @Transactional
    public EnvironmentView markEnvironmentInitialized(String providerEnvironmentKey) {
        List<TestEnvironmentEntity> environments = environmentRepository
                .findByProviderEnvironmentKey(providerEnvironmentKey)
                .map(List::of)
                .orElseGet(() -> environmentRepository
                        .findAllByResourcePoolKeyOrderByCreatedAtAsc(providerEnvironmentKey));
        if (environments.isEmpty()) {
            throw new ProjectCatalogNotFoundException("环境或资源池不存在: " + providerEnvironmentKey);
        }
        environments.forEach(TestEnvironmentEntity::markInitialized);
        environmentRepository.saveAllAndFlush(environments);
        return toEnvironmentView(environments.getFirst());
    }

    @Transactional
    public EnvironmentView requireEnvironmentForDeployment(String providerEnvironmentKey) {
        TestEnvironmentEntity environment = environmentRepository.findForDeploymentUpdate(providerEnvironmentKey)
                .orElseGet(() -> environmentRepository
                        .findAllByResourcePoolKeyOrderByCreatedAtAsc(providerEnvironmentKey).stream()
                        .findFirst()
                        .orElseThrow(() -> new ProjectCatalogNotFoundException(
                                "环境或资源池不存在: " + providerEnvironmentKey)));
        return toEnvironmentView(environment);
    }

    @Transactional(readOnly = true)
    public ProjectView getProject(UUID projectId) {
        TestProjectEntity project = requireProject(projectId);
        List<TestTargetEntity> targets = targetRepository.findAllByProjectIdOrderByCreatedAtAsc(projectId);
        List<TargetView> targetViews = targets.stream()
                .map(target -> toTargetView(target, List.of()))
                .toList();
        return toProjectView(project, targetViews);
    }

    @Transactional(readOnly = true)
    public ProjectView requireProjectView(UUID projectId) {
        return getProject(projectId);
    }

    @Transactional(readOnly = true)
    public TargetView requireTargetView(UUID targetId) {
        TestTargetEntity target = requireTarget(targetId);
        return toTargetView(target, List.of());
    }

    private TestProjectEntity requireProject(UUID projectId) {
        if (projectId == null) {
            throw new ProjectCatalogValidationException("projectId 不能为空");
        }
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectCatalogNotFoundException("项目不存在: " + projectId));
    }

    private TestTargetEntity requireTarget(UUID targetId) {
        if (targetId == null) {
            throw new ProjectCatalogValidationException("targetId 不能为空");
        }
        return targetRepository.findById(targetId)
                .orElseThrow(() -> new ProjectCatalogNotFoundException("被测对象不存在: " + targetId));
    }

    private ProjectView toProjectView(TestProjectEntity project, List<TargetView> targets) {
        TargetView primary = targets.isEmpty() ? null : targets.getFirst();
        return new ProjectView(
                project.getId(),
                project.getName(),
                project.getCode(),
                project.getState(),
                project.getTargetType() == null && primary != null ? primary.type() : project.getTargetType(),
                project.getRepositoryUrl() == null && primary != null ? primary.repositoryUrl() : project.getRepositoryUrl(),
                project.getDefaultBranch() == null && primary != null ? primary.defaultBranch() : project.getDefaultBranch(),
                project.getCiProvider(),
                project.getCiServerUrl(),
                project.getCiFolder(),
                project.getCiCredentialRef(),
                List.copyOf(targets)
        );
    }

    private TargetView toTargetView(TestTargetEntity target, List<EnvironmentView> environments) {
        return new TargetView(
                target.getId(),
                target.getProjectId(),
                target.getName(),
                target.getType(),
                target.getRepositoryUrl(),
                target.getDefaultBranch(),
                List.copyOf(environments)
        );
    }

    private EnvironmentView toEnvironmentView(TestEnvironmentEntity environment) {
        return new EnvironmentView(
                environment.getId(),
                environment.getTargetId(),
                environment.getName(),
                environment.getEndpoint(),
                environment.getProviderEnvironmentKey(),
                environment.getPlatform(),
                environment.getResourcePoolKey(),
                environment.getCapacity(),
                environment.isEnabled(),
                environment.isInitializeOnNextDeploy(),
                Map.copyOf(readJson(environment.getConfigJson(), OBJECT_MAP_TYPE)),
                Map.copyOf(readJson(environment.getSecretRefsJson(), STRING_MAP_TYPE))
        );
    }

    private String normalizedName(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ProjectCatalogValidationException(fieldName + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > 200) {
            throw new ProjectCatalogValidationException(fieldName + "不能超过 200 个字符");
        }
        return normalized;
    }

    private String normalizedCode(String value) {
        if (value == null || !value.matches("^[a-z][a-z0-9-]{1,62}$")) {
            throw new ProjectCatalogValidationException("项目编码必须匹配 ^[a-z][a-z0-9-]{1,62}$");
        }
        return value;
    }

    private String normalizedEndpoint(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 2048) throw new ProjectCatalogValidationException("endpoint 不能超过 2048 个字符");
        try {
            URI uri = new URI(value.trim());
            if (!uri.isAbsolute() || uri.getScheme() == null || uri.getScheme().isBlank() || uri.getUserInfo() != null) {
                throw new ProjectCatalogValidationException("endpoint 必须是不含用户凭据的绝对 URI");
            }
            return uri.toString();
        } catch (URISyntaxException exception) {
            throw new ProjectCatalogValidationException("endpoint 必须是合法 URI");
        }
    }

    private String normalizedProviderEnvironmentKey(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9][A-Za-z0-9._/-]{0,254}$")) {
            throw new ProjectCatalogValidationException("providerEnvironmentKey 格式错误");
        }
        return value.trim();
    }

    private int normalizedCapacity(int value) {
        if (value < 1 || value > 1000) {
            throw new ProjectCatalogValidationException("capacity 必须在 1 到 1000 之间");
        }
        return value;
    }

    private EnvironmentPlatform normalizedPlatform(EnvironmentPlatform value) {
        if (value == null) throw new ProjectCatalogValidationException("platform 不能为空");
        return value;
    }

    private String normalizedResourcePoolKey(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$")) {
            throw new ProjectCatalogValidationException("resourcePoolKey 格式错误");
        }
        return value.trim();
    }

    private SourceControl normalizedSourceControl(String repositoryUrl, String defaultBranch) {
        boolean repositoryMissing = repositoryUrl == null || repositoryUrl.isBlank();
        boolean branchMissing = defaultBranch == null || defaultBranch.isBlank();
        if (repositoryMissing && branchMissing) {
            return new SourceControl(null, null);
        }
        if (repositoryMissing || branchMissing) {
            throw new ProjectCatalogValidationException("repositoryUrl 和 defaultBranch 必须同时配置");
        }

        String normalizedRepository = repositoryUrl.trim();
        if (normalizedRepository.length() > 2048
                || normalizedRepository.indexOf('\r') >= 0
                || normalizedRepository.indexOf('\n') >= 0) {
            throw new ProjectCatalogValidationException("repositoryUrl 必须是长度不超过 2048 的单行地址");
        }
        if (normalizedRepository.contains("://")) {
            try {
                URI uri = new URI(normalizedRepository);
                if (!uri.isAbsolute() || uri.getUserInfo() != null) {
                    throw new ProjectCatalogValidationException("repositoryUrl 必须是不含用户凭据的绝对地址");
                }
            } catch (URISyntaxException exception) {
                throw new ProjectCatalogValidationException("repositoryUrl 必须是合法地址");
            }
        }

        String normalizedBranch = defaultBranch.trim();
        if (normalizedBranch.length() > 255
                || normalizedBranch.startsWith("-")
                || normalizedBranch.contains("..")
                || normalizedBranch.contains("@{")
                || normalizedBranch.chars().anyMatch(Character::isWhitespace)) {
            throw new ProjectCatalogValidationException("defaultBranch 不是合法 Git 分支名");
        }
        return new SourceControl(normalizedRepository, normalizedBranch);
    }

    private Map<String, Object> immutableObjectMap(Map<String, Object> value, String fieldName) {
        if (value == null) {
            throw new ProjectCatalogValidationException(fieldName + " 不能为空");
        }
        return Map.copyOf(value);
    }

    private Map<String, String> immutableStringMap(Map<String, String> value) {
        if (value == null) {
            throw new ProjectCatalogValidationException("secretRefs 不能为空");
        }
        value.forEach((key, reference) -> {
            if (key == null || key.isBlank() || reference == null || reference.isBlank() || reference.length() > 512) {
                throw new ProjectCatalogValidationException("secretRefs 的键和值必须非空，且引用不能超过 512 个字符");
            }
        });
        return Map.copyOf(value);
    }

    private void rejectSensitiveConfig(Map<String, Object> config) {
        config.keySet().forEach(key -> {
            if (key == null || key.isBlank()) {
                throw new ProjectCatalogValidationException("config 的键不能为空");
            }
            String normalized = key.replace("_", "")
                    .replace("-", "")
                    .toLowerCase(Locale.ROOT);
            if (SENSITIVE_CONFIG_KEYS.stream().anyMatch(normalized::contains)) {
                throw new ProjectCatalogValidationException(
                        "config 不能保存敏感字段，请改用 secretRefs: " + key
                );
            }
        });
    }

    private String writeJson(Map<?, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ProjectCatalogValidationException("配置无法序列化为 JSON");
        }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("项目资产中的 JSON 数据损坏", exception);
        }
    }

    private record SourceControl(String repositoryUrl, String defaultBranch) {
    }

}
