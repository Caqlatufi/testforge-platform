package io.testforge.runorchestrator.service.attempt;

import io.testforge.runorchestrator.model.attempt.AttemptState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AttemptStateMachine {

    private static final Map<AttemptState, Set<AttemptState>> TRANSITIONS = transitions();

    public boolean canTransition(AttemptState source, AttemptState target) {
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(target, "target 不能为空");
        return source == target || TRANSITIONS.get(source).contains(target);
    }

    public void requireTransition(AttemptState source, AttemptState target) {
        if (!canTransition(source, target)) {
            throw new AttemptStateTransitionException(source, target);
        }
    }

    private static Map<AttemptState, Set<AttemptState>> transitions() {
        EnumMap<AttemptState, Set<AttemptState>> transitions = new EnumMap<>(AttemptState.class);
        transitions.put(
                AttemptState.CREATED,
                Set.copyOf(EnumSet.of(AttemptState.RUNNING, AttemptState.CANCELLED, AttemptState.LOST))
        );
        transitions.put(
                AttemptState.RUNNING,
                Set.copyOf(EnumSet.of(
                        AttemptState.SUCCEEDED,
                        AttemptState.FAILED,
                        AttemptState.CANCELLED,
                        AttemptState.TIMEOUT,
                        AttemptState.LOST
                ))
        );
        for (AttemptState state : AttemptState.values()) {
            transitions.putIfAbsent(state, Set.of());
        }
        return Map.copyOf(transitions);
    }
}
