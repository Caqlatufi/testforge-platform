package io.testforge.runorchestrator.task.dag.release;

import io.testforge.casecatalog.workflow.compile.model.DependencyCondition;
import io.testforge.runorchestrator.task.model.TaskState;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 根据持久化 Task 状态释放 DAG 根节点和后继节点。
 *
 * <p>服务采用收敛式扫描：每次回调都会补齐之前已经 BLOCKED 但尚未传播的分支，因此进程在
 * 中间步骤退出后，至少一次回调仍可继续收敛。真正的幂等边界由 StateStore 的状态与版本比较更新提供。</p>
 */
public final class DagReleaseService {

    private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);

    private final DagReleaseStateStore stateStore;
    private final Clock clock;

    public DagReleaseService(DagReleaseStateStore stateStore) {
        this(stateStore, Clock.systemUTC());
    }

    public DagReleaseService(DagReleaseStateStore stateStore, Clock clock) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 将没有任何前置边的 CREATED/WAITING_DEPENDENCY Task 释放为 QUEUED。
     */
    public DagReleaseResult releaseRoots(UUID runId) {
        requireRunId(runId);
        return converge(runId, this::rootTransitions);
    }

    /**
     * 在一个 Task 进入终态后重新判定整个等待集合，并递归传播 BLOCKED。
     */
    public DagReleaseResult releaseSuccessors(UUID runId, UUID terminalTaskId) {
        requireRunId(runId);
        Objects.requireNonNull(terminalTaskId, "terminalTaskId must not be null");
        DagRunSnapshot snapshot = stateStore.load(runId);
        DagTaskSnapshot terminalTask = snapshot.tasks().stream()
                .filter(task -> task.taskId().equals(terminalTaskId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Task 不属于 Run: " + terminalTaskId
                ));
        if (!terminalTask.state().isTerminal()) {
            throw new IllegalArgumentException(
                    "只有终态 Task 可以触发后继释放: " + terminalTask.state()
            );
        }
        return converge(runId, this::successorTransitions);
    }

    private DagReleaseResult converge(
            UUID runId,
            Function<DagRunSnapshot, List<DagTaskTransition>> transitionPlanner
    ) {
        List<DagReleaseAction> actions = new ArrayList<>();
        int rounds = 0;
        int roundLimit = 32;
        while (true) {
            DagRunSnapshot snapshot = stateStore.load(runId);
            roundLimit = Math.max(roundLimit, snapshot.tasks().size() * 4 + 8);
            if (++rounds > roundLimit) {
                throw new DagReleaseConflictException(
                        "DAG 状态持续冲突，未能在限定轮次内收敛: " + runId
                );
            }

            List<DagTaskTransition> planned = transitionPlanner.apply(snapshot);
            if (planned.isEmpty()) {
                return new DagReleaseResult(actions);
            }

            for (DagTaskTransition transition : planned) {
                if (stateStore.compareAndSet(runId, transition)) {
                    actions.add(new DagReleaseAction(
                            transition.taskId(),
                            transition.expectedState(),
                            transition.targetState(),
                            transition.blockedByTaskId(),
                            transition.blockedReason()
                    ));
                }
            }
        }
    }

    private List<DagTaskTransition> rootTransitions(DagRunSnapshot snapshot) {
        Set<UUID> successors = snapshot.dependencies().stream()
                .map(DagDependency::successorTaskId)
                .collect(Collectors.toSet());
        Instant now = Instant.now(clock);
        return snapshot.tasks().stream()
                .filter(task -> !successors.contains(task.taskId()))
                .filter(task -> task.state() == TaskState.CREATED
                        || task.state() == TaskState.WAITING_DEPENDENCY)
                .sorted(Comparator.comparing(DagTaskSnapshot::taskId, UUID_ORDER))
                .map(task -> queue(task, now))
                .toList();
    }

    private List<DagTaskTransition> successorTransitions(DagRunSnapshot snapshot) {
        Map<UUID, DagTaskSnapshot> tasksById = snapshot.tasks().stream()
                .collect(Collectors.toMap(DagTaskSnapshot::taskId, Function.identity()));
        Map<UUID, List<DagDependency>> incoming = new HashMap<>();
        for (DagDependency dependency : snapshot.dependencies()) {
            incoming.computeIfAbsent(dependency.successorTaskId(), ignored -> new ArrayList<>())
                    .add(dependency);
        }

        Instant now = Instant.now(clock);
        List<DagTaskTransition> transitions = new ArrayList<>();
        snapshot.tasks().stream()
                .filter(task -> task.state() == TaskState.WAITING_DEPENDENCY)
                .filter(task -> incoming.containsKey(task.taskId()))
                .sorted(Comparator.comparing(DagTaskSnapshot::taskId, UUID_ORDER))
                .forEach(task -> decide(task, incoming.get(task.taskId()), tasksById, now)
                        .ifPresent(transitions::add));
        return List.copyOf(transitions);
    }

    private Optional<DagTaskTransition> decide(
            DagTaskSnapshot task,
            List<DagDependency> dependencies,
            Map<UUID, DagTaskSnapshot> tasksById,
            Instant now
    ) {
        List<DagDependency> ordered = dependencies.stream()
                .sorted(Comparator.comparing(DagDependency::predecessorTaskId, UUID_ORDER))
                .toList();
        boolean allSatisfied = true;
        for (DagDependency dependency : ordered) {
            DagTaskSnapshot predecessor = tasksById.get(dependency.predecessorTaskId());
            if (predecessor == null) {
                throw new IllegalArgumentException(
                        "依赖前置 Task 不存在: " + dependency.predecessorTaskId()
                );
            }
            if (dependency.condition() == DependencyCondition.ON_SUCCESS) {
                if (predecessor.state().isSuccessful()) {
                    continue;
                }
                if (predecessor.state().isTerminal()) {
                    return Optional.of(block(task, predecessor, now));
                }
                allSatisfied = false;
                continue;
            }
            if (!predecessor.state().isTerminal()) {
                allSatisfied = false;
            }
        }
        return allSatisfied ? Optional.of(queue(task, now)) : Optional.empty();
    }

    private DagTaskTransition queue(DagTaskSnapshot task, Instant now) {
        return new DagTaskTransition(
                task.taskId(),
                task.state(),
                task.version(),
                TaskState.QUEUED,
                null,
                null,
                now
        );
    }

    private DagTaskTransition block(
            DagTaskSnapshot task,
            DagTaskSnapshot predecessor,
            Instant now
    ) {
        return new DagTaskTransition(
                task.taskId(),
                task.state(),
                task.version(),
                TaskState.BLOCKED,
                predecessor.taskId(),
                "ON_SUCCESS 前置任务未成功: " + predecessor.taskId()
                        + " (" + predecessor.state() + ")",
                now
        );
    }

    private void requireRunId(UUID runId) {
        Objects.requireNonNull(runId, "runId must not be null");
    }
}
