package io.testforge.workergateway.device.registry.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(
        name = "worker_gateway_device_slot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_worker_gateway_device_identity",
                columnNames = {"worker_id", "device_id"}
        )
)
public class DeviceSlotEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "worker_id", nullable = false, updatable = false, length = 128)
    private String workerId;

    @Column(name = "device_id", nullable = false, updatable = false, length = 128)
    private String deviceId;

    @Column(name = "platform", nullable = false, length = 64)
    private String platform;

    @Column(name = "device_uri", nullable = false, length = 512)
    private String deviceUri;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "worker_gateway_device_feature",
            joinColumns = @JoinColumn(name = "device_slot_id")
    )
    @Column(name = "feature", nullable = false, length = 64)
    private Set<String> features = new LinkedHashSet<>();

    @Column(name = "resolution", length = 64)
    private String resolution;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    private Instant lastHeartbeatAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long persistenceVersion;

    protected DeviceSlotEntity() {
    }

    public DeviceSlotEntity(
            UUID id,
            String workerId,
            String deviceId,
            String platform,
            String deviceUri,
            Set<String> features,
            String resolution,
            Instant registeredAt
    ) {
        this.id = id;
        this.workerId = workerId;
        this.deviceId = deviceId;
        this.platform = platform;
        this.deviceUri = deviceUri;
        this.features.addAll(features);
        this.resolution = resolution;
        this.registeredAt = registeredAt;
        this.lastHeartbeatAt = registeredAt;
        this.updatedAt = registeredAt;
    }

    public void refreshRegistration(
            String platform,
            String deviceUri,
            Set<String> features,
            String resolution,
            Instant changedAt
    ) {
        this.platform = platform;
        this.deviceUri = deviceUri;
        this.features.clear();
        this.features.addAll(features);
        this.resolution = resolution;
        this.lastHeartbeatAt = changedAt;
        this.updatedAt = changedAt;
    }

    public void heartbeat(Instant heartbeatAt) {
        if (heartbeatAt.isAfter(lastHeartbeatAt)) {
            this.lastHeartbeatAt = heartbeatAt;
            this.updatedAt = heartbeatAt;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getPlatform() {
        return platform;
    }

    public String getDeviceUri() {
        return deviceUri;
    }

    public Set<String> getFeatures() {
        return features;
    }

    public String getResolution() {
        return resolution;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return persistenceVersion == null ? 0L : persistenceVersion;
    }
}
