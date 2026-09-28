package io.testforge.runorchestrator.entity.attempt;

import io.testforge.runorchestrator.model.attempt.AttemptState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "run_orchestrator_task_attempt",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_run_orchestrator_attempt_task_no",
                columnNames = {"task_id", "attempt_no"}
        )
)
public class TaskAttemptEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "worker_id", nullable = false, updatable = false, length = 128)
    private String workerId;

    @Column(name = "lease_token", nullable = false, updatable = false, unique = true)
    private UUID leaseToken;

    @Column(name = "lease_until", nullable = false)
    private Instant leaseUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private AttemptState state;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TaskAttemptEntity() {
    }

    public TaskAttemptEntity(
            UUID id,
            UUID taskId,
            int attemptNo,
            String workerId,
            UUID leaseToken,
            Instant leaseUntil,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id 不能为空");
        this.taskId = Objects.requireNonNull(taskId, "taskId 不能为空");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 必须大于等于 1");
        }
        this.attemptNo = attemptNo;
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId 不能为空");
        }
        this.workerId = workerId;
        this.leaseToken = Objects.requireNonNull(leaseToken, "leaseToken 不能为空");
        this.leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil 不能为空");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
        this.updatedAt = createdAt;
        this.state = AttemptState.CREATED;
    }

    /** 新建 Attempt 在与 Task 领取相同事务中进入 RUNNING，初始版本仍为 0。 */
    public void start(Instant startedAt) {
        Objects.requireNonNull(startedAt, "startedAt 不能为空");
        if (state != AttemptState.CREATED) {
            throw new IllegalStateException("只有 CREATED Attempt 可以启动: " + state);
        }
        state = AttemptState.RUNNING;
        updatedAt = startedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public String getWorkerId() {
        return workerId;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public AttemptState getState() {
        return state;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
