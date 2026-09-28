package io.testforge.workergateway.device.lease;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * DeviceSlot 的独占占用投影。
 *
 * <p>注册信息与能力标签属于 device.registry；本记录只保存租约真相，便于两个
 * 子域在 TFP-013 集成时通过稳定的 deviceSlotId 连接。</p>
 */
@Entity
@Table(
        name = "worker_gateway_device_lease",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_worker_gateway_device_lease_attempt",
                columnNames = "current_attempt_id"
        )
)
public class DeviceSlotLeaseEntity {

    @Id
    @Column(name = "device_slot_id", nullable = false, updatable = false)
    private UUID deviceSlotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private DeviceLeaseState state;

    @Column(name = "current_attempt_id")
    private UUID currentAttemptId;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "reserved_at")
    private Instant reservedAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_reason", length = 16)
    private DeviceLeaseReleaseReason releaseReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected DeviceSlotLeaseEntity() {
    }

    public DeviceSlotLeaseEntity(UUID deviceSlotId) {
        this.deviceSlotId = deviceSlotId;
        this.state = DeviceLeaseState.AVAILABLE;
    }

    public UUID getDeviceSlotId() {
        return deviceSlotId;
    }

    public DeviceLeaseState getState() {
        return state;
    }

    public UUID getCurrentAttemptId() {
        return currentAttemptId;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public Instant getReservedAt() {
        return reservedAt;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public DeviceLeaseReleaseReason getReleaseReason() {
        return releaseReason;
    }

    public long getVersion() {
        return version == null ? 0L : version;
    }
}
