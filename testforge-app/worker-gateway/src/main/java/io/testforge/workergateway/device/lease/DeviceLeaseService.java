package io.testforge.workergateway.device.lease;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * DeviceSlot 独占租约服务。
 *
 * <p>AVAILABLE/RESERVED 仲裁、Attempt 绑定、心跳和释放都由 MySQL 条件更新完成；
 * 服务不做 runner/platform/features 能力匹配。</p>
 */
public class DeviceLeaseService {

    private final DeviceSlotLeaseRepository repository;
    private final DeviceLeasePolicy policy;
    private final Clock clock;
    private final Supplier<UUID> tokenSupplier;

    public DeviceLeaseService(
            DeviceSlotLeaseRepository repository,
            DeviceLeasePolicy policy,
            Clock clock,
            Supplier<UUID> tokenSupplier
    ) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.policy = Objects.requireNonNull(policy, "policy 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier 不能为空");
    }

    /** 设备注册子域在设备可参与调度时调用；重复初始化不改变活动租约。 */
    @Transactional
    public DeviceLeaseSnapshot initializeAvailable(UUID deviceSlotId) {
        requireId(deviceSlotId, "deviceSlotId");
        Optional<DeviceSlotLeaseEntity> current = repository.findById(deviceSlotId);
        if (current.isPresent()) {
            return toSnapshot(current.get());
        }
        return toSnapshot(repository.saveAndFlush(new DeviceSlotLeaseEntity(deviceSlotId)));
    }

    /** AVAILABLE -> RESERVED；同一 Attempt 的重复请求返回首次租约。 */
    @Transactional
    public DeviceLeaseSnapshot reserve(UUID deviceSlotId, UUID attemptId) {
        requireId(deviceSlotId, "deviceSlotId");
        requireId(attemptId, "attemptId");
        Instant reservedAt = clock.instant();
        UUID leaseToken = Objects.requireNonNull(tokenSupplier.get(), "tokenSupplier 返回 null");
        int changed;
        try {
            changed = repository.reserveIfAvailable(
                    deviceSlotId,
                    attemptId,
                    leaseToken,
                    reservedAt,
                    reservedAt.plus(policy.leaseDuration())
            );
        } catch (DataIntegrityViolationException exception) {
            throw new DeviceLeaseUnavailableException(deviceSlotId, attemptId);
        }
        if (changed == 1) {
            return load(deviceSlotId);
        }
        DeviceLeaseSnapshot current = repository.findById(deviceSlotId)
                .map(this::toSnapshot)
                .orElseThrow(() -> new DeviceLeaseUnavailableException(deviceSlotId, attemptId));
        if (current.isReservedBy(attemptId) && !current.leaseUntil().isBefore(reservedAt)) {
            return current;
        }
        throw new DeviceLeaseUnavailableException(deviceSlotId, attemptId);
    }

    /** 只有仍未过期且 Attempt/token 完全匹配的 RESERVED 记录可以续租。 */
    @Transactional
    public DeviceLeaseSnapshot heartbeat(UUID deviceSlotId, UUID attemptId, UUID leaseToken) {
        requireId(deviceSlotId, "deviceSlotId");
        requireId(attemptId, "attemptId");
        requireId(leaseToken, "leaseToken");
        Instant acceptedAt = clock.instant();
        int changed = repository.heartbeat(
                deviceSlotId,
                attemptId,
                leaseToken,
                acceptedAt,
                acceptedAt.plus(policy.leaseDuration())
        );
        if (changed != 1) {
            throw new DeviceLeaseRejectedException(deviceSlotId, attemptId);
        }
        return load(deviceSlotId);
    }

    /** Attempt 心跳同时延长其已占用设备；没有设备租约时是无操作。 */
    @Transactional
    public Optional<DeviceLeaseSnapshot> heartbeatForAttempt(UUID attemptId) {
        requireId(attemptId, "attemptId");
        Optional<DeviceSlotLeaseEntity> current = repository.findByCurrentAttemptId(attemptId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        DeviceSlotLeaseEntity lease = current.get();
        return Optional.of(heartbeat(
                lease.getDeviceSlotId(), attemptId, lease.getLeaseToken()
        ));
    }

    @Transactional
    public boolean releaseCancelled(UUID attemptId) {
        return release(attemptId, DeviceLeaseReleaseReason.CANCELLED);
    }

    @Transactional
    public boolean releaseTerminal(UUID attemptId) {
        return release(attemptId, DeviceLeaseReleaseReason.TERMINAL);
    }

    /**
     * 扫描结果只是候选；每条记录仍以 deviceSlotId + attemptId + token + version + leaseUntil
     * 条件释放，避免与边界时刻的心跳互相覆盖。
     */
    @Transactional
    public DeviceLeaseReapResult releaseExpired() {
        Instant scannedAt = clock.instant();
        List<DeviceLeaseSnapshot> candidates = repository.findExpired(
                        scannedAt,
                        PageRequest.of(0, policy.reaperBatchSize())
                ).stream()
                .map(this::toSnapshot)
                .toList();
        List<UUID> released = new ArrayList<>(candidates.size());
        for (DeviceLeaseSnapshot candidate : candidates) {
            int changed = repository.releaseIfExpired(
                    candidate.deviceSlotId(),
                    candidate.currentAttemptId(),
                    candidate.leaseToken(),
                    candidate.version(),
                    scannedAt
            );
            if (changed == 1) {
                released.add(candidate.deviceSlotId());
            }
        }
        return new DeviceLeaseReapResult(scannedAt, candidates.size(), released);
    }

    @Transactional(readOnly = true)
    public DeviceLeaseSnapshot load(UUID deviceSlotId) {
        requireId(deviceSlotId, "deviceSlotId");
        return repository.findById(deviceSlotId)
                .map(this::toSnapshot)
                .orElseThrow(() -> new IllegalArgumentException("DeviceSlot 租约不存在: " + deviceSlotId));
    }

    private boolean release(UUID attemptId, DeviceLeaseReleaseReason reason) {
        requireId(attemptId, "attemptId");
        return repository.releaseByAttempt(attemptId, reason, clock.instant()) == 1;
    }

    private DeviceLeaseSnapshot toSnapshot(DeviceSlotLeaseEntity entity) {
        return new DeviceLeaseSnapshot(
                entity.getDeviceSlotId(),
                entity.getState(),
                entity.getCurrentAttemptId(),
                entity.getLeaseToken(),
                entity.getLeaseUntil(),
                entity.getReservedAt(),
                entity.getLastHeartbeatAt(),
                entity.getReleasedAt(),
                entity.getReleaseReason(),
                entity.getVersion()
        );
    }

    private static void requireId(UUID value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
    }
}
