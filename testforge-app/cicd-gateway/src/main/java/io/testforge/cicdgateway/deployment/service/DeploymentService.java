package io.testforge.cicdgateway.deployment.service;

import io.testforge.cicdgateway.deployment.entity.DeploymentProfileEntity;
import io.testforge.cicdgateway.deployment.entity.TargetDeploymentEntity;
import io.testforge.cicdgateway.deployment.model.*;
import io.testforge.cicdgateway.deployment.repo.DeploymentProfileRepository;
import io.testforge.cicdgateway.deployment.repo.TargetDeploymentRepository;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class DeploymentService {
    private static final Set<DeploymentState> ACTIVE = EnumSet.of(DeploymentState.PENDING, DeploymentState.TRIGGERED, DeploymentState.BUILDING);
    private final DeploymentProfileRepository profiles;
    private final TargetDeploymentRepository deployments;
    private final ProjectCatalogService projects;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public DeploymentService(DeploymentProfileRepository profiles, TargetDeploymentRepository deployments,
                             ProjectCatalogService projects, ApplicationEventPublisher events) {
        this(profiles, deployments, projects, events, Clock.systemUTC());
    }

    DeploymentService(DeploymentProfileRepository profiles, TargetDeploymentRepository deployments,
                      ProjectCatalogService projects, ApplicationEventPublisher events, Clock clock) {
        this.profiles = profiles; this.deployments = deployments; this.projects = projects; this.events = events; this.clock = clock;
    }

    @Transactional
    public DeploymentProfileView createProfile(UUID targetId, CreateDeploymentProfileCommand command) {
        projects.requireTargetView(targetId);
        validate(command);
        String name = command.name().trim();
        if (profiles.findByTargetIdAndName(targetId, name).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同名 DeploymentProfile 已存在");
        var normalized = new CreateDeploymentProfileCommand(name, trimUrl(command.serverUrl()), command.jobName().trim(),
                blankToNull(command.credentialRef()), command.buildConfigDigest().trim(), command.maxConcurrency(), command.ttlSeconds());
        return view(profiles.saveAndFlush(new DeploymentProfileEntity(UUID.randomUUID(), targetId, normalized, clock.instant())));
    }

    @Transactional(readOnly = true)
    public List<DeploymentProfileView> listProfiles(UUID targetId) {
        projects.requireTargetView(targetId);
        return profiles.findAllByTargetIdOrderByCreatedAtAsc(targetId).stream().map(this::view).toList();
    }

    @Transactional
    public TargetDeploymentView prepare(UUID targetId, UUID profileId, String resolvedCommit) {
        return prepare(targetId, profileId, resolvedCommit, null);
    }

    @Transactional
    public TargetDeploymentView prepare(UUID targetId, UUID profileId, String resolvedCommit,
                                        String environmentExternalId) {
        var target = projects.requireTargetView(targetId);
        if (target.repositoryUrl() == null || target.repositoryUrl().isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Target 未配置源码仓库");
        DeploymentProfileEntity profile = profiles.findByIdForUpdate(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "DeploymentProfile 不存在"));
        if (!profile.getTargetId().equals(targetId) || !profile.isEnabled())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DeploymentProfile 不属于目标或已停用");
        String commit = normalizeCommit(resolvedCommit);
        String environment = blankToNull(environmentExternalId);
        var testEnvironment = requireEnvironment(environment);
        boolean initializeEnvironment = testEnvironment != null && testEnvironment.initializeOnNextDeploy();
        String key = sha256(targetId + "|" + commit + "|" + profileId + "|"
                + profile.getBuildConfigDigest() + "|" + Objects.toString(environment, "DEFAULT")
                + "|initialize=" + initializeEnvironment);
        Optional<TargetDeploymentEntity> existing = deployments.findByDeploymentKey(key);
        if (existing.isPresent()) {
            TargetDeploymentEntity reusable = existing.get();
            reusable.expire(clock.instant());
            if (reusable.getState() != DeploymentState.EXPIRED
                    && reusable.getState() != DeploymentState.FAILED) return view(reusable);
            requireCapacity(profileId, profile.getMaxConcurrency());
            requireEnvironmentCapacity(testEnvironment, initializeEnvironment);
            reusable.restart(clock.instant());
            deployments.saveAndFlush(reusable);
            events.publishEvent(new DeploymentTriggerRequestedEvent(reusable.getId()));
            return view(reusable);
        }
        requireCapacity(profileId, profile.getMaxConcurrency());
        requireEnvironmentCapacity(testEnvironment, initializeEnvironment);
        TargetDeploymentEntity entity = new TargetDeploymentEntity(UUID.randomUUID(), targetId, profileId,
                commit, key, environment, initializeEnvironment, clock.instant());
        try {
            entity = deployments.saveAndFlush(entity);
        } catch (DataIntegrityViolationException duplicate) {
            return view(deployments.findByDeploymentKey(key).orElseThrow(() -> duplicate));
        }
        events.publishEvent(new DeploymentTriggerRequestedEvent(entity.getId()));
        return view(entity);
    }

    @Transactional
    public TargetDeploymentView callback(UUID deploymentId, DeploymentCallbackCommand command) {
        Objects.requireNonNull(command.callbackKey(), "callbackKey 不能为空");
        Objects.requireNonNull(command.status(), "status 不能为空");
        TargetDeploymentEntity entity = requireDeployment(deploymentId);
        DeploymentProfileEntity profile = requireProfile(entity.getProfileId());
        String payloadHash = sha256(command.toString());
        try {
            boolean changed = entity.callback(command, payloadHash, clock.instant(), profile.getTtlSeconds());
            if (changed && entity.getState() == DeploymentState.READY && entity.isInitializationRequested()
                    && entity.getEnvironmentExternalId() != null) {
                projects.markEnvironmentInitialized(entity.getEnvironmentExternalId());
            }
            if (changed) events.publishEvent(new DeploymentChangedEvent(entity.getId(), entity.getState()));
            return view(entity);
        } catch (IllegalStateException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict);
        }
    }

    @Transactional(readOnly = true)
    public TargetDeploymentView get(UUID id) { return view(requireDeployment(id)); }

    @Transactional(readOnly = true)
    public List<TargetDeploymentView> list(UUID targetId) {
        return deployments.findAllByTargetIdOrderByCreatedAtDesc(targetId).stream().map(this::view).toList();
    }

    DeploymentProfileEntity requireProfile(UUID id) {
        return profiles.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "DeploymentProfile 不存在"));
    }

    TargetDeploymentEntity requireDeployment(UUID id) {
        return deployments.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deployment 不存在"));
    }

    TargetDeploymentRepository deployments() { return deployments; }
    ProjectCatalogService projects() { return projects; }
    Clock clock() { return clock; }
    void publishChanged(TargetDeploymentEntity deployment) {
        events.publishEvent(new DeploymentChangedEvent(deployment.getId(), deployment.getState()));
    }

    @Transactional
    public int expireReadyDeployments() {
        List<TargetDeploymentEntity> expired = deployments.findAllByStateAndExpiresAtLessThanEqual(
                DeploymentState.READY,
                clock.instant()
        );
        int changed = 0;
        for (TargetDeploymentEntity deployment : expired) {
            if (deployment.expire(clock.instant())) {
                deployments.save(deployment);
                publishChanged(deployment);
                changed++;
            }
        }
        return changed;
    }

    @Transactional
    public int failStaleActiveDeployments(Duration activeTimeout) {
        if (activeTimeout == null || activeTimeout.isZero() || activeTimeout.isNegative()) {
            throw new IllegalArgumentException("activeTimeout 必须大于 0");
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(activeTimeout);
        List<TargetDeploymentEntity> stale = deployments.findAllByStateInAndUpdatedAtLessThanEqual(ACTIVE, cutoff);
        int changed = 0;
        for (TargetDeploymentEntity deployment : stale) {
            if (deployment.failIfActiveBefore(cutoff, now,
                    "Jenkins 发布超过活动时限且未收到终态回调: " + activeTimeout)) {
                deployments.save(deployment);
                publishChanged(deployment);
                changed++;
            }
        }
        return changed;
    }

    @Transactional
    public boolean failActive(UUID deploymentId, String summary) {
        if (deploymentId == null) throw new IllegalArgumentException("deploymentId 不能为空");
        String reason = blank(summary) ? "关联执行已取消" : summary.trim();
        TargetDeploymentEntity deployment = requireDeployment(deploymentId);
        if (!deployment.failIfActive(clock.instant(), reason)) return false;
        deployments.save(deployment);
        publishChanged(deployment);
        return true;
    }

    private void validate(CreateDeploymentProfileCommand command) {
        if (command == null || blank(command.name()) || blank(command.serverUrl()) || blank(command.jobName()) || blank(command.buildConfigDigest()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name、serverUrl、jobName、buildConfigDigest 不能为空");
        if (!command.buildConfigDigest().matches("^sha256:[0-9a-f]{64}$"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "buildConfigDigest 必须是 sha256 摘要");
        if (command.maxConcurrency() < 1 || command.maxConcurrency() > 50 || command.ttlSeconds() < 60 || command.ttlSeconds() > 604800)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxConcurrency 或 ttlSeconds 超出范围");
    }

    private DeploymentProfileView view(DeploymentProfileEntity e) { return new DeploymentProfileView(e.getId(), e.getTargetId(), e.getName(), e.getProvider(), e.getServerUrl(), e.getJobName(), e.getCredentialRef(), e.getBuildConfigDigest(), e.getMaxConcurrency(), e.getTtlSeconds(), e.isEnabled(), e.getCreatedAt()); }
    private TargetDeploymentView view(TargetDeploymentEntity e) { return new TargetDeploymentView(e.getId(), e.getTargetId(), e.getProfileId(), e.getResolvedCommit(), e.getDeploymentKey(), e.getState(), e.getProviderRunId(), e.getEndpoint(), e.getArtifactUri(), e.getArtifactDigest(), e.getSummary(), e.getCreatedAt(), e.getUpdatedAt(), e.getExpiresAt(), e.getVersion(), e.getEnvironmentExternalId(), e.isInitializationRequested()); }
    private String normalizeCommit(String value) { if (value == null || !value.trim().matches("^[0-9a-fA-F]{40}$")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resolvedCommit 必须是完整 Commit SHA"); return value.trim().toLowerCase(Locale.ROOT); }
    private String trimUrl(String value) { return value.trim().replaceAll("/+$", ""); }
    private void requireCapacity(UUID profileId, int maxConcurrency) {
        if (deployments.countByProfileIdAndStateIn(profileId, ACTIVE) >= maxConcurrency)
            throw new ResponseStatusException(HttpStatus.LOCKED, "DeploymentProfile 并发配额已满");
    }
    private io.testforge.projectcatalog.model.EnvironmentView requireEnvironment(String providerEnvironmentKey) {
        if (providerEnvironmentKey == null) return null;
        var environment = projects.requireEnvironmentForDeployment(providerEnvironmentKey);
        if (!environment.enabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "测试环境不存在或已停用: " + providerEnvironmentKey);
        }
        return environment;
    }
    private void requireEnvironmentCapacity(
            io.testforge.projectcatalog.model.EnvironmentView environment, boolean initializeEnvironment) {
        if (environment == null) return;
        long activeCount = deployments.countByEnvironmentExternalIdAndStateIn(
                environment.providerEnvironmentKey(), ACTIVE);
        if (initializeEnvironment && activeCount > 0) {
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "测试环境正在初始化: " + environment.name());
        }
        if (activeCount >= environment.capacity()) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "测试环境容量已满: " + environment.name());
        }
    }
    private String blankToNull(String value) { return blank(value) ? null : value.trim(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String sha256(String value) { try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
