package io.testforge.runorchestrator.task.entity;

import io.testforge.casecatalog.workflow.compile.model.DependencyCondition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "run_orchestrator_task_dependency",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_run_orchestrator_task_dependency",
                columnNames = {"run_id", "predecessor_task_id", "successor_task_id"}
        )
)
public class TaskDependencyEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "predecessor_task_id", nullable = false, updatable = false)
    private UUID predecessorTaskId;

    @Column(name = "successor_task_id", nullable = false, updatable = false)
    private UUID successorTaskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "dependency_condition", nullable = false, updatable = false, length = 20)
    private DependencyCondition condition;

    protected TaskDependencyEntity() {
    }

    public TaskDependencyEntity(
            UUID id,
            UUID runId,
            UUID predecessorTaskId,
            UUID successorTaskId,
            DependencyCondition condition
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.predecessorTaskId = Objects.requireNonNull(predecessorTaskId,
                "predecessorTaskId must not be null");
        this.successorTaskId = Objects.requireNonNull(successorTaskId,
                "successorTaskId must not be null");
        this.condition = Objects.requireNonNull(condition, "condition must not be null");
        if (predecessorTaskId.equals(successorTaskId)) {
            throw new IllegalArgumentException("Task 不能依赖自身");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getPredecessorTaskId() {
        return predecessorTaskId;
    }

    public UUID getSuccessorTaskId() {
        return successorTaskId;
    }

    public DependencyCondition getCondition() {
        return condition;
    }
}
