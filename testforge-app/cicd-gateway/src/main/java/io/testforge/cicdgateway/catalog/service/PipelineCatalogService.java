package io.testforge.cicdgateway.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.cicdgateway.catalog.model.PipelineEnvironmentRef;
import io.testforge.cicdgateway.catalog.model.PipelineRef;
import io.testforge.cicdgateway.deployment.entity.DeploymentProfileEntity;
import io.testforge.cicdgateway.deployment.model.CreateDeploymentProfileCommand;
import io.testforge.cicdgateway.deployment.repo.DeploymentProfileRepository;
import io.testforge.cicdgateway.provider.CicdCredentialResolver;
import io.testforge.projectcatalog.model.EnvironmentView;
import io.testforge.projectcatalog.model.EnvironmentPlatform;
import io.testforge.projectcatalog.revision.RevisionSelector;
import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PipelineCatalogService {
    private static final String JOB_TREE = "jobs%5Bname%2CdisplayName%2CfullName%2Curl%2C_class%2Ccolor%2Cbuildable%5D";
    private static final int DEFAULT_MAX_CONCURRENCY = 4;
    private static final int DEFAULT_TTL_SECONDS = 3600;
    private static final Pattern REMOTE_PATTERN = Pattern.compile("<remote>([^<]+)</remote>");
    private static final Pattern SCRIPT_PATH_PATTERN = Pattern.compile("<scriptPath>([^<]+)</scriptPath>");

    private final DeploymentProfileRepository profiles;
    private final ProjectCatalogService projects;
    private final CicdCredentialResolver credentials;
    private final ObjectMapper objectMapper;
    private final JenkinsConnectionProperties connection;
    private final HttpClient client;

    @Autowired
    public PipelineCatalogService(DeploymentProfileRepository profiles, ProjectCatalogService projects,
                                  CicdCredentialResolver credentials, ObjectMapper objectMapper,
                                  JenkinsConnectionProperties connection) {
        this(profiles, projects, credentials, objectMapper, connection,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    PipelineCatalogService(DeploymentProfileRepository profiles, ProjectCatalogService projects,
                           CicdCredentialResolver credentials, ObjectMapper objectMapper,
                           JenkinsConnectionProperties connection, HttpClient client) {
        this.profiles = profiles;
        this.projects = projects;
        this.credentials = credentials;
        this.objectMapper = objectMapper;
        this.connection = connection;
        this.client = client;
    }

    /** Jenkins owns the Pipeline catalog; DeploymentProfile is only a project binding. */
    @Transactional(readOnly = true)
    public List<PipelineRef> list(UUID projectId) {
        var target = projects.requireExecutionTarget(projectId);
        var project = projects.requireProjectView(projectId);
        List<DeploymentProfileEntity> bindings = profiles.findAllByTargetIdOrderByCreatedAtAsc(target.id());
        if (!connection.isConfigured()) return bindings.stream().map(this::toLegacyRef).toList();
        Map<String, DeploymentProfileEntity> byJob = new LinkedHashMap<>();
        bindings.forEach(binding -> byJob.put(binding.getJobName(), binding));
        return listJobs(folderApiUrl()).stream()
                .filter(pipeline -> byJob.containsKey(pipeline.jobName())
                        || matchesProjectPipeline(pipeline, project.repositoryUrl()))
                .map(pipeline -> {
                    DeploymentProfileEntity binding = byJob.get(pipeline.jobName());
                    return new PipelineRef(pipeline.externalId(), pipeline.name(), pipeline.provider(),
                            pipeline.serverUrl(), pipeline.jobName(), pipeline.revision(),
                            pipeline.enabled() && (binding == null || binding.isEnabled()), pipeline.kind());
                })
                .toList();
    }

    private boolean matchesProjectPipeline(PipelineRef pipeline, String repositoryUrl) {
        ProjectRepository project = projectRepository(repositoryUrl);
        if (project == null) return false;
        JenkinsJobMetadata metadata = readJobMetadata(pipeline);
        return metadata != null
                && project.repositoryName().equalsIgnoreCase(repositoryName(metadata.remote()))
                && project.scriptPath().equals(metadata.scriptPath().replace('\\', '/'));
    }

    private JenkinsJobMetadata readJobMetadata(PipelineRef pipeline) {
        try {
            String xml = new String(get(jobUrl(pipeline.serverUrl(), pipeline.jobName()) + "/config.xml",
                    connection.getCredentialRef(), "Jenkins Pipeline 配置读取失败"), StandardCharsets.UTF_8);
            Matcher remote = REMOTE_PATTERN.matcher(xml);
            if (!remote.find()) return null;
            Matcher scriptPath = SCRIPT_PATH_PATTERN.matcher(xml);
            return new JenkinsJobMetadata(remote.group(1).trim(),
                    scriptPath.find() ? scriptPath.group(1).trim() : "Jenkinsfile");
        } catch (ResponseStatusException ignored) {
            return null;
        }
    }

    private ProjectRepository projectRepository(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) return null;
        try {
            Path candidate = Path.of(repositoryUrl).toAbsolutePath().normalize();
            Path root = candidate;
            while (root != null && !Files.exists(root.resolve(".git"))) root = root.getParent();
            if (root != null) {
                Path relative = root.relativize(candidate);
                String prefix = relative.toString().replace('\\', '/');
                String scriptPath = prefix.isBlank() ? "Jenkinsfile" : prefix + "/Jenkinsfile";
                return new ProjectRepository(repositoryName(root.toString()), scriptPath);
            }
        } catch (InvalidPathException ignored) {
            // Remote URLs are handled below.
        }
        return new ProjectRepository(repositoryName(repositoryUrl), "Jenkinsfile");
    }

    private String repositoryName(String value) {
        String normalized = value.replace('\\', '/');
        int query = normalized.indexOf('?');
        if (query >= 0) normalized = normalized.substring(0, query);
        int fragment = normalized.indexOf('#');
        if (fragment >= 0) normalized = normalized.substring(0, fragment);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return name.toLowerCase(Locale.ROOT).endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }

    @Transactional
    public Selection validate(UUID projectId, String pipelineExternalId, String environmentExternalId,
                              RevisionSelector selector) {
        UUID pipelineId = parsePipelineId(pipelineExternalId);
        PipelineEnvironmentRef environment = requireEnvironment(globalEnvironments(projectId), environmentExternalId);
        if (!connection.isConfigured()) {
            DeploymentProfileEntity profile = requireOwnedLegacy(projectId, pipelineId);
            if (!profile.isEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Pipeline 已禁用");
            return new Selection(toLegacyRef(profile), environment, profile.getId());
        }

        PipelineRef parent = requireJenkinsPipeline(projectId, pipelineId);
        if (!parent.enabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Pipeline 已禁用");
        PipelineRef executable = resolveExecutablePipeline(projectId, parent, selector);
        String digest = readJobDigest(executable);
        UUID profileId = synchronizeExecutionProfile(projectId, executable, digest);
        PipelineRef versioned = new PipelineRef(parent.externalId(), executable.name(), parent.provider(),
                parent.serverUrl(), executable.jobName(), digest, executable.enabled(), parent.kind());
        return new Selection(versioned, environment, profileId);
    }

    /** Test Job 只声明平台；资源池由平台根据当前环境实例目录自动选择。 */
    @Transactional
    public Selection validateForPlatform(UUID projectId, String pipelineExternalId,
                                         EnvironmentPlatform platform, RevisionSelector selector) {
        EnvironmentPlatform requested = platform == null ? EnvironmentPlatform.WINDOWS : platform;
        PipelineRef requestedPipeline = requireJenkinsPipeline(projectId, parsePipelineId(pipelineExternalId));
        List<EnvironmentView> candidates = projects.listEnvironments(true, requested).stream()
                .filter(EnvironmentView::enabled)
                .toList();
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "没有可用的 " + requested + " 测试环境实例");
        }
        List<EnvironmentView> explicitMatches = candidates.stream()
                .filter(environment -> supportsPipeline(environment, requestedPipeline.jobName()))
                .toList();
        List<EnvironmentView> legacyCandidates = candidates.stream()
                .filter(environment -> configuredPipelineJobs(environment).isEmpty())
                .toList();
        List<EnvironmentView> compatible = explicitMatches.isEmpty() ? legacyCandidates : explicitMatches;
        if (compatible.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "没有兼容 Pipeline " + requestedPipeline.name() + " 的 " + requested + " 测试环境实例");
        }
        var selectedGroup = compatible.stream()
                .collect(java.util.stream.Collectors.groupingBy(EnvironmentView::resourcePoolKey))
                .entrySet().stream()
                .sorted(java.util.Comparator
                        .<Map.Entry<String, List<EnvironmentView>>>comparingInt(entry -> entry.getValue().size())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .findFirst().orElseThrow();
        EnvironmentView representative = selectedGroup.getValue().getFirst();
        Selection validated = validate(projectId, pipelineExternalId,
                representative.id().toString(), selector);
        String poolKey = selectedGroup.getKey();
        List<String> providerKeys = selectedGroup.getValue().stream()
                .map(EnvironmentView::providerEnvironmentKey)
                .distinct()
                .toList();
        String providerKey = providerKeys.size() == 1 ? providerKeys.getFirst() : poolKey;
        PipelineEnvironmentRef automatic = new PipelineEnvironmentRef(
                "auto:" + requested.name() + ":" + poolKey,
                requested.name() + " 自动分配（" + selectedGroup.getValue().size() + " 台）",
                providerKey,
                requested.name(),
                poolKey,
                selectedGroup.getValue().size()
        );
        return new Selection(validated.pipeline(), automatic, validated.deploymentProfileId());
    }

    private boolean supportsPipeline(EnvironmentView environment, String jobName) {
        return configuredPipelineJobs(environment).stream().anyMatch(jobName::equals);
    }

    private List<String> configuredPipelineJobs(EnvironmentView environment) {
        Object configured = environment.config() == null ? null : environment.config().get("pipelineJobNames");
        if (configured instanceof Iterable<?> values) {
            List<String> result = new ArrayList<>();
            for (Object value : values) {
                String normalized = value == null ? null : value.toString().trim();
                if (normalized != null && !normalized.isEmpty()) result.add(normalized);
            }
            return List.copyOf(result);
        }
        if (configured instanceof String value && !value.isBlank()) {
            return List.of(value.trim());
        }
        return List.of();
    }

    /** Compatibility for old callers: execute the project's default branch. */
    @Transactional
    public Selection validate(UUID projectId, String pipelineExternalId, String environmentExternalId) {
        return validate(projectId, pipelineExternalId, environmentExternalId,
                new RevisionSelector(RevisionType.DEFAULT_BRANCH, null));
    }

    private PipelineRef resolveExecutablePipeline(UUID projectId, PipelineRef parent, RevisionSelector selector) {
        if (!"MULTIBRANCH".equals(parent.kind())) return parent;
        var project = projects.requireProjectView(projectId);
        RevisionType type = selector == null || selector.type() == null ? RevisionType.DEFAULT_BRANCH : selector.type();
        String desiredRef = switch (type) {
            case DEFAULT_BRANCH, COMMIT -> project.defaultBranch();
            case BRANCH, TAG -> selector.value();
        };
        if (desiredRef == null || desiredRef.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "无法确定 Pipeline 分支");
        }
        return listJobs(jobUrl(parent.serverUrl(), parent.jobName()) + "/api/json?tree=" + JOB_TREE).stream()
                .filter(job -> desiredRef.equals(job.name()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Jenkins Multibranch 尚未发现分支或标签: " + desiredRef));
    }

    private List<PipelineRef> listJobs(String apiUrl) {
        byte[] body = get(apiUrl, connection.getCredentialRef(), "Jenkins Pipeline 目录读取失败");
        try {
            JsonNode jobs = objectMapper.readTree(body).path("jobs");
            if (!jobs.isArray()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jenkins Pipeline 目录响应缺少 jobs");
            List<PipelineRef> result = new ArrayList<>();
            for (JsonNode job : jobs) {
                String type = job.path("_class").asText("");
                String normalizedType = type.toLowerCase(Locale.ROOT);
                String kind = normalizedType.contains("workflowmultibranchproject") ? "MULTIBRANCH" : "PIPELINE";
                boolean supported = type.isBlank() || "MULTIBRANCH".equals(kind)
                        || normalizedType.contains("workflowjob") || normalizedType.contains("freestyleproject");
                if (!supported) continue;
                String name = job.path("displayName").asText(job.path("name").asText("")).trim();
                String fullName = job.path("fullName").asText(job.path("name").asText("")).trim();
                if (name.isBlank() || fullName.isBlank()) continue;
                boolean enabled = !"disabled".equalsIgnoreCase(job.path("color").asText())
                        && job.path("buildable").asBoolean(true);
                result.add(new PipelineRef(externalId(connection.getServerUrl(), fullName), name, "JENKINS",
                        trimUrl(connection.getServerUrl()), fullName,
                        sha256(trimUrl(connection.getServerUrl()) + "|" + fullName), enabled, kind));
            }
            return List.copyOf(result);
        } catch (ResponseStatusException known) {
            throw known;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jenkins Pipeline 目录响应无法解析", error);
        }
    }

    private PipelineRef requireJenkinsPipeline(UUID projectId, UUID pipelineId) {
        return list(projectId).stream().filter(candidate -> candidate.externalId().equals(pipelineId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Pipeline 未绑定当前 Project，或已从 Jenkins 目录移除"));
    }

    private String readJobDigest(PipelineRef pipeline) {
        byte[] body = get(jobUrl(pipeline.serverUrl(), pipeline.jobName()) + "/config.xml",
                connection.getCredentialRef(), "Jenkins Pipeline 配置读取失败");
        return sha256(new String(body, StandardCharsets.UTF_8));
    }

    private List<PipelineEnvironmentRef> globalEnvironments(UUID projectId) {
        projects.requireProjectView(projectId);
        return projects.listEnvironments(true, null).stream()
                .filter(EnvironmentView::enabled)
                .map(environment -> new PipelineEnvironmentRef(environment.id().toString(), environment.name(),
                        environment.providerEnvironmentKey(), environment.platform().name(),
                        environment.resourcePoolKey(), environment.capacity()))
                .toList();
    }

    private byte[] get(String url, String credentialRef, String errorPrefix) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET();
        CicdCredentialResolver.Credential credential = credentials.resolve(credentialRef);
        if (credential != null) {
            String raw = credential.username() + ":" + credential.token();
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        }
        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, errorPrefix + "，HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, errorPrefix + "（请求被中断）", interrupted);
        } catch (ResponseStatusException known) {
            throw known;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, errorPrefix, error);
        }
    }

    private UUID synchronizeExecutionProfile(UUID projectId, PipelineRef pipeline, String digest) {
        var target = projects.requireExecutionTarget(projectId);
        String profileName = pipeline.name().length() <= 128 ? pipeline.name() : pipeline.name().substring(0, 128);
        CreateDeploymentProfileCommand command = new CreateDeploymentProfileCommand(profileName,
                pipeline.serverUrl(), pipeline.jobName(), blankToNull(connection.getCredentialRef()), digest,
                DEFAULT_MAX_CONCURRENCY, DEFAULT_TTL_SECONDS);
        DeploymentProfileEntity profile = profiles.findByTargetIdAndJobName(target.id(), pipeline.jobName())
                .orElseGet(() -> new DeploymentProfileEntity(UUID.randomUUID(), target.id(), command, Instant.now()));
        profile.synchronize(command);
        return profiles.saveAndFlush(profile).getId();
    }

    private PipelineEnvironmentRef requireEnvironment(List<PipelineEnvironmentRef> environments, String externalId) {
        return environments.stream().filter(item -> item.externalId().equals(externalId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "TestForge 测试环境不存在或已禁用: " + externalId));
    }

    private DeploymentProfileEntity requireOwnedLegacy(UUID projectId, UUID pipelineId) {
        var target = projects.requireExecutionTarget(projectId);
        DeploymentProfileEntity profile = profiles.findById(pipelineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pipeline 不存在"));
        if (!profile.getTargetId().equals(target.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pipeline 不属于指定 Project");
        }
        return profile;
    }

    private PipelineRef toLegacyRef(DeploymentProfileEntity profile) {
        String revision = sha256(profile.getServerUrl() + "|" + profile.getJobName()
                + "|" + profile.getBuildConfigDigest());
        return new PipelineRef(profile.getId(), profile.getName(), profile.getProvider().name(),
                profile.getServerUrl(), profile.getJobName(), revision, profile.isEnabled(), "PIPELINE");
    }

    private String folderApiUrl() {
        String base = trimUrl(connection.getServerUrl());
        String folder = connection.getFolder();
        if (folder != null && !folder.isBlank()) base += "/" + jobPath(folder);
        return base + "/api/json?tree=" + JOB_TREE;
    }

    private String jobUrl(String serverUrl, String jobName) { return trimUrl(serverUrl) + "/" + jobPath(jobName); }

    private String jobPath(String value) {
        return java.util.Arrays.stream(value.split("/"))
                .filter(segment -> !segment.isBlank())
                .map(segment -> "job/" + URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .reduce((left, right) -> left + "/" + right).orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "Jenkins Folder/Job 名称无效"));
    }

    private UUID externalId(String serverUrl, String fullName) {
        return UUID.nameUUIDFromBytes(("jenkins:" + trimUrl(serverUrl) + "|" + fullName)
                .getBytes(StandardCharsets.UTF_8));
    }

    private UUID parsePipelineId(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pipelineExternalId 不是有效 UUID");
        }
    }

    private String trimUrl(String value) { return value.trim().replaceAll("/+$", ""); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record JenkinsJobMetadata(String remote, String scriptPath) { }
    private record ProjectRepository(String repositoryName, String scriptPath) { }
    public record Selection(PipelineRef pipeline, PipelineEnvironmentRef environment, UUID deploymentProfileId) { }
}
