package io.testforge.runorchestrator.task.dag.release;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record DagRunSnapshot(
        UUID runId,
        List<DagTaskSnapshot> tasks,
        List<DagDependency> dependencies
) {

    public DagRunSnapshot {
        Objects.requireNonNull(runId, "runId must not be null");
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks must not be null"));
        dependencies = List.copyOf(Objects.requireNonNull(
                dependencies,
                "dependencies must not be null"
        ));

        Set<UUID> taskIds = new HashSet<>();
        for (DagTaskSnapshot task : tasks) {
            if (!taskIds.add(task.taskId())) {
                throw new IllegalArgumentException("DAG 包含重复 Task: " + task.taskId());
            }
        }
        Set<DependencyKey> dependencyKeys = new HashSet<>();
        for (DagDependency dependency : dependencies) {
            if (!taskIds.contains(dependency.predecessorTaskId())
                    || !taskIds.contains(dependency.successorTaskId())) {
                throw new IllegalArgumentException("依赖边引用了不属于 Run 的 Task: " + dependency);
            }
            DependencyKey key = new DependencyKey(
                    dependency.predecessorTaskId(),
                    dependency.successorTaskId()
            );
            if (!dependencyKeys.add(key)) {
                throw new IllegalArgumentException("DAG 包含重复依赖边: " + key);
            }
        }
    }

    private record DependencyKey(UUID predecessorTaskId, UUID successorTaskId) {
    }
}
