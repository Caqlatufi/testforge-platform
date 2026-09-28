package io.testforge.runorchestrator.run.service;

import io.testforge.runorchestrator.run.model.RunState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Run 的显式状态迁移规则。聚合允许 Run 从 QUEUED 直接收敛终态，
 * 但任何终态都不可被另一个并发结论覆盖。
 */
public final class RunStateMachine {

    private static final Map<RunState, Set<RunState>> TRANSITIONS = transitions();

    public boolean canTransition(RunState source, RunState target) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        return source == target || TRANSITIONS.get(source).contains(target);
    }

    public void requireTransition(RunState source, RunState target) {
        if (!canTransition(source, target)) {
            throw new RunStateConflictException("非法 Run 状态迁移: " + source + " -> " + target);
        }
    }

    private static Map<RunState, Set<RunState>> transitions() {
        EnumMap<RunState, Set<RunState>> transitions = new EnumMap<>(RunState.class);
        transitions.put(RunState.QUEUED, Set.copyOf(EnumSet.of(
                RunState.RUNNING,
                RunState.CANCELLING,
                RunState.SUCCEEDED,
                RunState.FAILED,
                RunState.COMPLETED_WITH_WARNINGS
        )));
        transitions.put(RunState.RUNNING, Set.copyOf(EnumSet.of(
                RunState.CANCELLING,
                RunState.SUCCEEDED,
                RunState.FAILED,
                RunState.COMPLETED_WITH_WARNINGS
        )));
        transitions.put(RunState.CANCELLING, Set.of(RunState.CANCELLED));
        for (RunState state : RunState.values()) {
            transitions.putIfAbsent(state, Set.of());
        }
        return Map.copyOf(transitions);
    }
}
