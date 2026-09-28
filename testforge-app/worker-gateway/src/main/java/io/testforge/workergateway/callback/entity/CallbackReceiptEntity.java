package io.testforge.workergateway.callback.entity;

import io.testforge.workergateway.callback.model.CallbackDisposition;
import io.testforge.workergateway.callback.model.CallbackReceiptResponse;
import io.testforge.workergateway.callback.model.CallbackStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "worker_gateway_callback_receipt",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_worker_gateway_callback_attempt_key",
                columnNames = {"attempt_id", "callback_key"}
        )
)
public class CallbackReceiptEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "attempt_id", nullable = false, updatable = false)
    private UUID attemptId;

    @Column(name = "callback_key", nullable = false, updatable = false)
    private UUID callbackKey;

    @Column(name = "payload_hash", nullable = false, updatable = false, length = 64, columnDefinition = "CHAR(64)")
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposition", length = 24)
    private CallbackDisposition disposition;

    @Column(name = "task_id")
    private UUID taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", length = 32)
    private CallbackStatus resultStatus;

    @Column(name = "reason", length = 128)
    private String reason;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected CallbackReceiptEntity() {
    }

    public CallbackReceiptEntity(
            UUID id,
            UUID attemptId,
            UUID callbackKey,
            String payloadHash,
            Instant receivedAt
    ) {
        this.id = Objects.requireNonNull(id, "id 不能为空");
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId 不能为空");
        this.callbackKey = Objects.requireNonNull(callbackKey, "callbackKey 不能为空");
        this.payloadHash = Objects.requireNonNull(payloadHash, "payloadHash 不能为空");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt 不能为空");
    }

    public void accept(UUID completedTaskId, CallbackStatus completedStatus) {
        this.disposition = CallbackDisposition.ACCEPTED;
        this.taskId = Objects.requireNonNull(completedTaskId, "completedTaskId 不能为空");
        this.resultStatus = Objects.requireNonNull(completedStatus, "completedStatus 不能为空");
        this.reason = null;
    }

    public void stale() {
        this.disposition = CallbackDisposition.STALE;
        this.taskId = null;
        this.resultStatus = null;
        this.reason = "STALE_CALLBACK";
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public CallbackDisposition getDisposition() {
        return disposition;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public UUID getCallbackKey() {
        return callbackKey;
    }

    public String getReason() {
        return reason;
    }

    public CallbackReceiptResponse response(CallbackDisposition responseDisposition) {
        return new CallbackReceiptResponse(
                attemptId,
                callbackKey,
                responseDisposition,
                disposition,
                taskId,
                resultStatus,
                reason,
                receivedAt
        );
    }
}
