package io.testforge.workergateway.device.registry.service;

import io.testforge.workergateway.device.registry.entity.DeviceSlotEntity;
import io.testforge.workergateway.device.lease.DeviceLeaseState;
import io.testforge.workergateway.device.lease.DeviceLeaseService;
import io.testforge.workergateway.device.registry.model.DeviceRequirement;
import io.testforge.workergateway.device.registry.model.DeviceSlotStatus;
import io.testforge.workergateway.device.registry.model.DeviceSlotView;
import io.testforge.workergateway.device.registry.model.RegisterDeviceSlotCommand;
import io.testforge.workergateway.device.registry.repo.DeviceSlotRepository;
import io.testforge.workergateway.registry.model.WorkerStatus;
import io.testforge.workergateway.registry.service.WorkerRegistryService;
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

public class DeviceRegistryService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final int MAX_FEATURES = 64;

    private final DeviceSlotRepository repository;
    private final WorkerRegistryService workerRegistryService;
    private final Clock clock;
    private final Duration offlineTimeout;
    private final DeviceLeaseService leaseService;

    public DeviceRegistryService(
            DeviceSlotRepository repository,
            WorkerRegistryService workerRegistryService,
            Clock clock,
            Duration offlineTimeout
    ) {
        this(repository, workerRegistryService, clock, offlineTimeout, null);
    }

    public DeviceRegistryService(
            DeviceSlotRepository repository,
            WorkerRegistryService workerRegistryService,
            Clock clock,
            Duration offlineTimeout,
            DeviceLeaseService leaseService
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.workerRegistryService = Objects.requireNonNull(
                workerRegistryService,
                "workerRegistryService must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.offlineTimeout = Objects.requireNonNull(offlineTimeout, "offlineTimeout must not be null");
        this.leaseService = leaseService;
        if (offlineTimeout.isZero() || offlineTimeout.isNegative()) {
            throw new IllegalArgumentException("offlineTimeout must be positive");
        }
    }

    /**
     * 以 workerId + deviceId 作为 Worker 声明的稳定设备身份；服务端 slotId 首次生成后不再改变。
     */
    @Transactional
    public DeviceSlotView register(RegisterDeviceSlotCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String workerId = normalizeIdentifier(command.workerId(), "workerId", 128);
        requireOnlineWorker(workerId);
        String deviceId = normalizeIdentifier(command.deviceId(), "deviceId", 128);
        String platform = normalizeToken(command.platform(), "platform");
        String deviceUri = normalizeIdentifier(command.deviceUri(), "deviceUri", 512);
        Set<String> features = normalizeFeatures(command.features());
        String resolution = normalizeOptional(command.resolution(), "resolution", 64);
        Instant now = clock.instant();

        var existing = repository.findByWorkerIdAndDeviceId(workerId, deviceId);
        DeviceSlotEntity slot = existing.orElseGet(() -> new DeviceSlotEntity(
                UUID.randomUUID(),
                workerId,
                deviceId,
                platform,
                deviceUri,
                features,
                resolution,
                now
        ));
        if (existing.isPresent()) {
            slot.refreshRegistration(platform, deviceUri, features, resolution, now);
        }
        DeviceSlotEntity saved = repository.saveAndFlush(slot);
        if (leaseService != null) {
            leaseService.initializeAvailable(saved.getId());
        }
        return toView(saved, now);
    }

    @Transactional
    public DeviceSlotView heartbeat(String workerId, String deviceId) {
        String normalizedWorkerId = normalizeIdentifier(workerId, "workerId", 128);
        requireOnlineWorker(normalizedWorkerId);
        DeviceSlotEntity slot = requireSlot(normalizedWorkerId, deviceId);
        Instant now = clock.instant();
        slot.heartbeat(now);
        return toView(repository.saveAndFlush(slot), now);
    }

    @Transactional(readOnly = true)
    public DeviceSlotView get(String workerId, String deviceId) {
        Instant now = clock.instant();
        return toView(requireSlot(workerId, deviceId), now);
    }

    @Transactional(readOnly = true)
    public List<DeviceSlotView> list(DeviceSlotStatus status) {
        Instant now = clock.instant();
        return repository.findAllByOrderByWorkerIdAscDeviceIdAsc().stream()
                .map(slot -> toView(slot, now))
                .filter(slot -> status == null || slot.status() == status)
                .toList();
    }

    /**
     * 候选设备必须同时满足 Worker 在线、设备心跳在线、平台和全部能力标签。
     * 排序优先保留更稀缺的设备（额外能力更少），再选最近心跳，最终以稳定身份兜底。
     */
    @Transactional(readOnly = true)
    public List<DeviceSlotView> findMatching(DeviceRequirement requirement) {
        NormalizedRequirement normalized = normalizeRequirement(requirement);
        Instant now = clock.instant();
        Comparator<DeviceSlotView> order = Comparator
                .comparingInt((DeviceSlotView slot) ->
                        slot.features().size() - normalized.requiredFeatures().size())
                .thenComparing(DeviceSlotView::lastHeartbeatAt, Comparator.reverseOrder())
                .thenComparing(DeviceSlotView::workerId)
                .thenComparing(DeviceSlotView::deviceId);

        return repository.findAllByOrderByWorkerIdAscDeviceIdAsc().stream()
                .map(slot -> toView(slot, now))
                .filter(slot -> slot.status() == DeviceSlotStatus.AVAILABLE)
                .filter(slot -> "ANY".equals(normalized.platform())
                        || slot.platform().equals(normalized.platform()))
                .filter(slot -> slot.features().containsAll(normalized.requiredFeatures()))
                .sorted(order)
                .toList();
    }

    private DeviceSlotEntity requireSlot(String rawWorkerId, String rawDeviceId) {
        String workerId = normalizeIdentifier(rawWorkerId, "workerId", 128);
        String deviceId = normalizeIdentifier(rawDeviceId, "deviceId", 128);
        return repository.findByWorkerIdAndDeviceId(workerId, deviceId)
                .orElseThrow(() -> new DeviceSlotNotFoundException(
                        "DeviceSlot 不存在: " + workerId + "/" + deviceId
                ));
    }

    private void requireOnlineWorker(String workerId) {
        if (!workerIsOnline(workerId)) {
            throw new DeviceRegistryValidationException("Worker 当前不在线: " + workerId);
        }
    }

    private boolean workerIsOnline(String workerId) {
        return workerRegistryService.get(workerId).status() == WorkerStatus.ONLINE;
    }

    private DeviceSlotView toView(DeviceSlotEntity slot, Instant now) {
        Instant expiresAt = slot.getLastHeartbeatAt().plus(offlineTimeout);
        Set<String> features = new LinkedHashSet<>(new TreeSet<>(slot.getFeatures()));
        DeviceSlotStatus status = resolveStatus(slot, now, expiresAt);
        return new DeviceSlotView(
                slot.getId(),
                slot.getWorkerId(),
                slot.getDeviceId(),
                slot.getPlatform(),
                slot.getDeviceUri(),
                features,
                slot.getResolution(),
                status,
                slot.getRegisteredAt(),
                slot.getLastHeartbeatAt(),
                expiresAt,
                slot.getUpdatedAt(),
                slot.getVersion()
        );
    }

    private DeviceSlotStatus resolveStatus(
            DeviceSlotEntity slot, Instant now, Instant expiresAt
    ) {
        if (!expiresAt.isAfter(now) || !workerIsOnline(slot.getWorkerId())) {
            return DeviceSlotStatus.OFFLINE;
        }
        if (leaseService != null
                && leaseService.load(slot.getId()).state() == DeviceLeaseState.RESERVED) {
            return DeviceSlotStatus.RESERVED;
        }
        return DeviceSlotStatus.AVAILABLE;
    }

    private NormalizedRequirement normalizeRequirement(DeviceRequirement requirement) {
        if (requirement == null) {
            throw new DeviceRegistryValidationException("设备能力要求不能为空");
        }
        return new NormalizedRequirement(
                normalizeToken(requirement.platform(), "platform"),
                normalizeFeatures(requirement.requiredFeatures())
        );
    }

    private String normalizeIdentifier(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new DeviceRegistryValidationException(field + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new DeviceRegistryValidationException(field + " 不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private String normalizeOptional(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new DeviceRegistryValidationException(field + " 不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private String normalizeToken(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DeviceRegistryValidationException(field + " 不能为空");
        }
        String normalized = value.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        if (!TOKEN_PATTERN.matcher(normalized).matches()) {
            throw new DeviceRegistryValidationException(field + " 无法转换为合法能力名称");
        }
        return normalized;
    }

    private Set<String> normalizeFeatures(Set<String> values) {
        if (values == null) {
            return Set.of();
        }
        if (values.size() > MAX_FEATURES) {
            throw new DeviceRegistryValidationException("features 不能超过 " + MAX_FEATURES + " 项");
        }
        var normalized = new TreeSet<String>();
        for (String value : values) {
            normalized.add(normalizeToken(value, "feature"));
        }
        return Set.copyOf(normalized);
    }

    private record NormalizedRequirement(String platform, Set<String> requiredFeatures) {
    }
}
