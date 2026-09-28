package io.testforge.observability.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "observability_execution_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_execution_event_key", columnNames = "event_key")
)
public class ExecutionEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", nullable = false, updatable = false)
    private UUID eventKey;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "task_id", updatable = false)
    private UUID taskId;

    @Column(name = "attempt_id", updatable = false)
    private UUID attemptId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String type;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "payload_json", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    protected ExecutionEventEntity() { }

    public ExecutionEventEntity(
            UUID eventKey, UUID runId, UUID taskId, UUID attemptId,
            String type, Instant occurredAt, String payloadJson
    ) {
        this.eventKey = eventKey;
        this.runId = runId;
        this.taskId = taskId;
        this.attemptId = attemptId;
        this.type = type;
        this.occurredAt = occurredAt;
        this.payloadJson = payloadJson;
    }

    public Long getId() { return id; }
    public UUID getEventKey() { return eventKey; }
    public UUID getRunId() { return runId; }
    public UUID getTaskId() { return taskId; }
    public UUID getAttemptId() { return attemptId; }
    public String getType() { return type; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getPayloadJson() { return payloadJson; }
}
