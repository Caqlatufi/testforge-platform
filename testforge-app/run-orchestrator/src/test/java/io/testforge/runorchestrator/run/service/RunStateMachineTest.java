package io.testforge.runorchestrator.run.service;

import io.testforge.runorchestrator.run.model.RunState;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStateMachineTest {

    private final RunStateMachine stateMachine = new RunStateMachine();

    @Test
    void shouldExposeLegalOperationalAndAggregationTransitions() {
        assertThat(legalTargets(RunState.QUEUED)).containsExactlyInAnyOrder(
                RunState.RUNNING,
                RunState.CANCELLING,
                RunState.SUCCEEDED,
                RunState.FAILED,
                RunState.COMPLETED_WITH_WARNINGS
        );
        assertThat(legalTargets(RunState.RUNNING)).containsExactlyInAnyOrder(
                RunState.CANCELLING,
                RunState.SUCCEEDED,
                RunState.FAILED,
                RunState.COMPLETED_WITH_WARNINGS
        );
        assertThat(legalTargets(RunState.CANCELLING)).containsExactly(RunState.CANCELLED);
    }

    @Test
    void shouldRejectSkippingCancellationAndProtectEveryTerminalConclusion() {
        assertThatThrownBy(() -> stateMachine.requireTransition(RunState.QUEUED, RunState.CANCELLED))
                .isInstanceOf(RunStateConflictException.class)
                .hasMessageContaining("QUEUED -> CANCELLED");

        for (RunState terminal : EnumSet.of(
                RunState.SUCCEEDED,
                RunState.FAILED,
                RunState.COMPLETED_WITH_WARNINGS,
                RunState.CANCELLED
        )) {
            for (RunState target : RunState.values()) {
                if (target != terminal) {
                    assertThat(stateMachine.canTransition(terminal, target))
                            .as("%s 终态不能迁移到 %s", terminal, target)
                            .isFalse();
                }
            }
        }
    }

    @Test
    void shouldTreatSameStateAsIdempotentObservation() {
        for (RunState state : RunState.values()) {
            assertThat(stateMachine.canTransition(state, state)).isTrue();
        }
    }

    private Set<RunState> legalTargets(RunState source) {
        EnumSet<RunState> result = EnumSet.noneOf(RunState.class);
        for (RunState target : RunState.values()) {
            if (source != target && stateMachine.canTransition(source, target)) {
                result.add(target);
            }
        }
        return result;
    }
}
