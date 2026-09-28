package io.testforge.workergateway.registry.service;

import io.testforge.workergateway.registry.entity.WorkerNodeEntity;
import io.testforge.workergateway.registry.model.RegisterWorkerCommand;
import io.testforge.workergateway.registry.model.WorkerNodeView;
import io.testforge.workergateway.registry.model.WorkerRequirement;
import io.testforge.workergateway.registry.model.WorkerStatus;
import io.testforge.workergateway.registry.repo.WorkerNodeRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

public class WorkerRegistryService {

    private static final Pattern PROTOCOL_PATTERN = Pattern.compile("^1\\.([0-9]+)$");
    private static final Pattern CAPABILITY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final int MAX_CAPABILITIES = 64;

    private final WorkerNodeRepository repository;
    private final Clock clock;
    private final Duration offlineTimeout;

    public WorkerRegistryService(
            WorkerNodeRepository repository,
            Clock clock,
            Duration offlineTimeout
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.offlineTimeout = Objects.requireNonNull(offlineTimeout, "offlineTimeout must not be null");
        if (offlineTimeout.isZero() || offlineTimeout.isNegative()) {
            throw new IllegalArgumentException("offlineTimeout must be positive");
        }
    }

    @Transactional
    public WorkerNodeView register(RegisterWorkerCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String workerId = normalizeWorkerId(command.workerId());
        String protocolVersion = normalizeProtocolVersion(command.protocolVersion());
        Set<String> capabilities = normalizeCapabilities(command.capabilities(), true);
        int maxConcurrency = normalizeMaxConcurrency(command.maxConcurrency());
        Instant now = clock.instant();

        var existing = repository.findByWorkerId(workerId);
        WorkerNodeEntity worker = existing.orElseGet(() -> new WorkerNodeEntity(
                UUID.randomUUID(),
                workerId,
                protocolVersion,
                capabilities,
                maxConcurrency,
                now
        ));
        if (existing.isPresent()) {
            worker.refreshRegistration(protocolVersion, capabilities, maxConcurrency, now);
        }
        return toView(repository.saveAndFlush(worker), now);
    }

    @Transactional
    public WorkerNodeView heartbeat(String workerId) {
        WorkerNodeEntity worker = requireWorker(workerId);
        Instant now = clock.instant();
        worker.heartbeat(now);
        return toView(repository.saveAndFlush(worker), now);
    }

    @Transactional(readOnly = true)
    public WorkerNodeView get(String workerId) {
        Instant now = clock.instant();
        return toView(requireWorker(workerId), now);
    }

    @Transactional(readOnly = true)
    public List<WorkerNodeView> list(WorkerStatus status) {
        Instant now = clock.instant();
        return repository.findAllByOrderByWorkerIdAsc().stream()
                .map(worker -> toView(worker, now))
                .filter(worker -> status == null || worker.status() == status)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkerNodeView> findMatching(WorkerRequirement requirement) {
        NormalizedRequirement normalized = normalizeRequirement(requirement);
        Instant now = clock.instant();
        return repository.findAllByOrderByWorkerIdAsc().stream()
                .filter(worker -> isOnline(worker, now))
                .filter(worker -> protocolCompatible(worker.getProtocolVersion(), normalized.protocolVersion()))
                .filter(worker -> hasRunner(worker.getCapabilities(), normalized.runner()))
                .filter(worker -> hasPlatform(worker.getCapabilities(), normalized.platform()))
                .filter(worker -> worker.getCapabilities().containsAll(normalized.requiredFeatures()))
                .map(worker -> toView(worker, now))
                .sorted(Comparator.comparing(WorkerNodeView::workerId))
                .toList();
    }

    private WorkerNodeEntity requireWorker(String rawWorkerId) {
        String workerId = normalizeWorkerId(rawWorkerId);
        return repository.findByWorkerId(workerId)
                .orElseThrow(() -> new WorkerNotFoundException("Worker 不存在: " + workerId));
    }

    private WorkerNodeView toView(WorkerNodeEntity worker, Instant now) {
        Instant expiresAt = worker.getLastHeartbeatAt().plus(offlineTimeout);
        Set<String> capabilities = new LinkedHashSet<>(new TreeSet<>(worker.getCapabilities()));
        return new WorkerNodeView(
                worker.getId(),
                worker.getWorkerId(),
                worker.getProtocolVersion(),
                capabilities,
                worker.getMaxConcurrency(),
                expiresAt.isAfter(now) ? WorkerStatus.ONLINE : WorkerStatus.OFFLINE,
                worker.getRegisteredAt(),
                worker.getLastHeartbeatAt(),
                expiresAt,
                worker.getUpdatedAt(),
                worker.getVersion()
        );
    }

    private boolean isOnline(WorkerNodeEntity worker, Instant now) {
        return worker.getLastHeartbeatAt().plus(offlineTimeout).isAfter(now);
    }

    private NormalizedRequirement normalizeRequirement(WorkerRequirement requirement) {
        if (requirement == null) {
            throw new WorkerRegistryValidationException("能力要求不能为空");
        }
        String protocolVersion = normalizeProtocolVersion(requirement.protocolVersion());
        String runner = normalizeToken(requirement.runner(), "runner");
        String platform = normalizeToken(requirement.platform(), "platform");
        Set<String> requiredFeatures = normalizeCapabilities(requirement.requiredFeatures(), false);
        return new NormalizedRequirement(protocolVersion, runner, platform, requiredFeatures);
    }

    private String normalizeWorkerId(String value) {
        if (value == null || value.isBlank()) {
            throw new WorkerRegistryValidationException("workerId 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new WorkerRegistryValidationException("workerId 不能超过 128 个字符");
        }
        return normalized;
    }

    private String normalizeProtocolVersion(String value) {
        if (value == null || !PROTOCOL_PATTERN.matcher(value.trim()).matches()) {
            throw new WorkerRegistryValidationException("protocolVersion 必须是受支持的 1.x 版本");
        }
        return value.trim();
    }

    private Set<String> normalizeCapabilities(Set<String> values, boolean requireNonEmpty) {
        if (values == null || (requireNonEmpty && values.isEmpty())) {
            throw new WorkerRegistryValidationException("capabilities 至少包含一项");
        }
        if (values.size() > MAX_CAPABILITIES) {
            throw new WorkerRegistryValidationException("capabilities 不能超过 " + MAX_CAPABILITIES + " 项");
        }
        var normalized = new TreeSet<String>();
        for (String value : values) {
            if (value == null || !CAPABILITY_PATTERN.matcher(value.trim()).matches()) {
                throw new WorkerRegistryValidationException("能力名称必须符合 ^[A-Z][A-Z0-9_]{0,63}$");
            }
            normalized.add(value.trim());
        }
        return Set.copyOf(normalized);
    }

    private int normalizeMaxConcurrency(int value) {
        if (value < 1 || value > 128) {
            throw new WorkerRegistryValidationException("maxConcurrency 必须在 1 到 128 之间");
        }
        return value;
    }

    private String normalizeToken(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new WorkerRegistryValidationException(field + " 不能为空");
        }
        String normalized = value.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        if (!CAPABILITY_PATTERN.matcher(normalized).matches()) {
            throw new WorkerRegistryValidationException(field + " 无法转换为合法能力名称");
        }
        return normalized;
    }

    private boolean protocolCompatible(String workerProtocol, String requiredProtocol) {
        int workerMinor = Integer.parseInt(workerProtocol.substring(workerProtocol.indexOf('.') + 1));
        int requiredMinor = Integer.parseInt(requiredProtocol.substring(requiredProtocol.indexOf('.') + 1));
        return workerMinor >= requiredMinor;
    }

    private boolean hasRunner(Set<String> capabilities, String runner) {
        return capabilities.contains(runner) || capabilities.contains("RUNNER_" + runner);
    }

    private boolean hasPlatform(Set<String> capabilities, String platform) {
        return "ANY".equals(platform)
                || capabilities.contains(platform)
                || capabilities.contains("PLATFORM_" + platform);
    }

    private record NormalizedRequirement(
            String protocolVersion,
            String runner,
            String platform,
            Set<String> requiredFeatures
    ) {
    }
}
