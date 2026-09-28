package io.testforge.dispatcher.outbox.entity;

import io.testforge.dispatcher.outbox.model.OutboxClaim;
import io.testforge.dispatcher.outbox.model.OutboxEventView;
import io.testforge.dispatcher.outbox.model.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "dispatcher_outbox_event",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_dispatcher_outbox_event_key",
                columnNames = "event_key"
        )
)
public class OutboxEventEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_key", nullable = false, updatable = false, length = 200)
    private String eventKey;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 64)
    private String aggregateType;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "payload_hash", nullable = false, updatable = false, length = 71)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "delivery_attempts", nullable = false)
    private int deliveryAttempts;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "lease_owner", length = 120)
    private String leaseOwner;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "persistence_version", nullable = false)
    private long persistenceVersion;

    protected OutboxEventEntity() {
    }

    public OutboxEventEntity(
            UUID id,
            String eventKey,
            UUID aggregateId,
            String aggregateType,
            String eventType,
            String payload,
            String payloadHash,
            Instant createdAt
    ) {
        this(id, eventKey, aggregateId, aggregateType, eventType, payload, payloadHash, createdAt, createdAt);
    }

    public OutboxEventEntity(
            UUID id,
            String eventKey,
            UUID aggregateId,
            String aggregateType,
            String eventType,
            String payload,
            String payloadHash,
            Instant createdAt,
            Instant availableAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.eventKey = requireText(eventKey, "eventKey", 200);
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        this.aggregateType = requireText(aggregateType, "aggregateType", 64);
        this.eventType = requireText(eventType, "eventType", 100);
        this.payload = requireText(payload, "payload", Integer.MAX_VALUE);
        this.payloadHash = requireText(payloadHash, "payloadHash", 71);
        this.status = OutboxStatus.PENDING;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.availableAt = Objects.requireNonNull(availableAt, "availableAt must not be null");
        this.updatedAt = createdAt;
    }

    public OutboxClaim claim(String owner, UUID token, Instant now, Duration leaseDuration) {
        if (!isClaimableAt(now)) {
            throw new IllegalStateException("Outbox 事件当前不可领取: " + id);
        }
        this.status = OutboxStatus.CLAIMED;
        this.leaseOwner = requireText(owner, "owner", 120);
        this.leaseToken = Objects.requireNonNull(token, "token must not be null");
        this.leaseUntil = now.plus(Objects.requireNonNull(leaseDuration, "leaseDuration must not be null"));
        this.deliveryAttempts++;
        this.updatedAt = now;
        return toClaim();
    }

    public boolean markPublished(UUID token, Instant now) {
        if (!ownsClaim(token) || status == OutboxStatus.PUBLISHED) {
            return false;
        }
        status = OutboxStatus.PUBLISHED;
        publishedAt = Objects.requireNonNull(now, "now must not be null");
        updatedAt = now;
        clearLease();
        lastError = null;
        return true;
    }

    public boolean reschedule(UUID token, Instant now, Duration delay, String error) {
        if (!ownsClaim(token) || status == OutboxStatus.PUBLISHED) {
            return false;
        }
        status = OutboxStatus.PENDING;
        availableAt = Objects.requireNonNull(now, "now must not be null")
                .plus(Objects.requireNonNull(delay, "delay must not be null"));
        updatedAt = now;
        lastError = truncateError(error);
        clearLease();
        return true;
    }

    public boolean isClaimableAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return (status == OutboxStatus.PENDING && !availableAt.isAfter(now))
                || (status == OutboxStatus.CLAIMED && leaseUntil != null && !leaseUntil.isAfter(now));
    }

    public boolean hasSameFingerprint(UUID candidateAggregateId, String candidateType, String candidateHash) {
        return aggregateId.equals(candidateAggregateId)
                && eventType.equals(candidateType)
                && payloadHash.equals(candidateHash);
    }

    public OutboxClaim toClaim() {
        return new OutboxClaim(
                id,
                eventKey,
                aggregateId,
                aggregateType,
                eventType,
                payload,
                deliveryAttempts,
                leaseOwner,
                leaseToken,
                leaseUntil
        );
    }

    public OutboxEventView toView() {
        return new OutboxEventView(
                id,
                eventKey,
                aggregateId,
                aggregateType,
                eventType,
                payload,
                payloadHash,
                status,
                deliveryAttempts,
                availableAt,
                leaseOwner,
                leaseToken,
                leaseUntil,
                publishedAt,
                lastError,
                createdAt,
                updatedAt
        );
    }

    public UUID getId() {
        return id;
    }

    public String getEventKey() {
        return eventKey;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    private boolean ownsClaim(UUID token) {
        return status == OutboxStatus.CLAIMED && leaseToken != null && leaseToken.equals(token);
    }

    private void clearLease() {
        leaseOwner = null;
        leaseToken = null;
        leaseUntil = null;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " 必须为长度不超过 " + maxLength + " 的非空字符串");
        }
        return value;
    }

    private static String truncateError(String error) {
        String normalized = error == null || error.isBlank() ? "unknown publish failure" : error.trim();
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
    }
}
