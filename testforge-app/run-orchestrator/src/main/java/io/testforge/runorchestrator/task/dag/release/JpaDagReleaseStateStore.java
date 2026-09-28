package io.testforge.runorchestrator.task.dag.release;

import io.testforge.runorchestrator.task.entity.TaskDependencyEntity;
import io.testforge.runorchestrator.task.entity.TestTaskEntity;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.repo.TaskDependencyRepository;
import io.testforge.runorchestrator.task.repo.TestTaskRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 以 MySQL/JPA Task 状态作为 DAG 唯一真相源的 Store。
 *
 * <p>读取快照只用于规划；最终更新始终经过状态与 {@code @Version} 双条件 CAS。
 * Repository 的 bulk update 会清理持久化上下文，避免后续 Run 聚合读到旧 Task。</p>
 */
public class JpaDagReleaseStateStore implements DagReleaseStateStore {

    private final TestTaskRepository taskRepository;
    private final TaskDependencyRepository dependencyRepository;

    public JpaDagReleaseStateStore(
            TestTaskRepository taskRepository,
            TaskDependencyRepository dependencyRepository
    ) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.dependencyRepository = Objects.requireNonNull(
                dependencyRepository,
                "dependencyRepository must not be null"
        );
    }

    @Override
    @Transactional(readOnly = true)
    public DagRunSnapshot load(UUID runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        List<DagTaskSnapshot> tasks = taskRepository.findAllByRunIdOrderBySequenceNoAsc(runId)
                .stream()
                .map(this::toSnapshot)
                .toList();
        List<DagDependency> dependencies = dependencyRepository
                .findAllByRunIdOrderBySuccessorTaskIdAscPredecessorTaskIdAsc(runId)
                .stream()
                .map(this::toDependency)
                .toList();
        return new DagRunSnapshot(runId, tasks, dependencies);
    }

    @Override
    @Transactional
    public boolean compareAndSet(UUID runId, DagTaskTransition transition) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(transition, "transition must not be null");
        Instant completedAt = transition.targetState() == TaskState.BLOCKED
                ? transition.occurredAt()
                : null;
        return taskRepository.compareAndSetDagState(
                runId,
                transition.taskId(),
                transition.expectedState(),
                transition.expectedVersion(),
                transition.targetState(),
                transition.blockedByTaskId(),
                transition.blockedReason(),
                transition.occurredAt(),
                completedAt
        ) == 1;
    }

    private DagTaskSnapshot toSnapshot(TestTaskEntity task) {
        return new DagTaskSnapshot(
                task.getId(),
                task.getState(),
                task.isRequired(),
                task.getPersistenceVersion()
        );
    }

    private DagDependency toDependency(TaskDependencyEntity dependency) {
        return new DagDependency(
                dependency.getPredecessorTaskId(),
                dependency.getSuccessorTaskId(),
                dependency.getCondition()
        );
    }
}
