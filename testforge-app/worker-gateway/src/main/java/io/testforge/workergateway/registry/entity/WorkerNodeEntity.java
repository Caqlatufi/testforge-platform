package io.testforge.workergateway.registry.entity;

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
        name = "worker_gateway_worker_node",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_worker_gateway_worker_id",
                columnNames = "worker_id"
        )
)
public class WorkerNodeEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "worker_id", nullable = false, updatable = false, length = 128)
    private String workerId;

    @Column(name = "protocol_version", nullable = false, length = 16)
    private String protocolVersion;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "worker_gateway_worker_capability",
            joinColumns = @JoinColumn(name = "worker_node_id")
    )
    @Column(name = "capability", nullable = false, length = 64)
    private Set<String> capabilities = new LinkedHashSet<>();

    @Column(name = "max_concurrency", nullable = false)
    private int maxConcurrency;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    private Instant lastHeartbeatAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long persistenceVersion;

    protected WorkerNodeEntity() {
    }

    public WorkerNodeEntity(
            UUID id,
            String workerId,
            String protocolVersion,
            Set<String> capabilities,
            int maxConcurrency,
            Instant registeredAt
    ) {
        this.id = id;
        this.workerId = workerId;
        this.protocolVersion = protocolVersion;
        this.capabilities.addAll(capabilities);
        this.maxConcurrency = maxConcurrency;
        this.registeredAt = registeredAt;
        this.lastHeartbeatAt = registeredAt;
        this.updatedAt = registeredAt;
    }

    public void refreshRegistration(
            String protocolVersion,
            Set<String> capabilities,
            int maxConcurrency,
            Instant changedAt
    ) {
        this.protocolVersion = protocolVersion;
        this.capabilities.clear();
        this.capabilities.addAll(capabilities);
        this.maxConcurrency = maxConcurrency;
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

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public Set<String> getCapabilities() {
        return capabilities;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
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
