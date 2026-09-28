package io.testforge.runorchestrator.service.attempt;

import io.testforge.runorchestrator.model.attempt.AttemptState;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttemptStateMachineTest {

    private final AttemptStateMachine stateMachine = new AttemptStateMachine();

    @Test
    void shouldExposeTheExplicitLegalTransitionMatrix() {
        assertThat(legalTargets(AttemptState.CREATED))
                .containsExactlyInAnyOrder(
                        AttemptState.RUNNING,
                        AttemptState.CANCELLED,
                        AttemptState.LOST
                );
        assertThat(legalTargets(AttemptState.RUNNING))
                .containsExactlyInAnyOrder(
                        AttemptState.SUCCEEDED,
                        AttemptState.FAILED,
                        AttemptState.CANCELLED,
                        AttemptState.TIMEOUT,
                        AttemptState.LOST
                );
    }

    @Test
    void shouldRejectSkippingRunningAndProtectEveryTerminalState() {
        assertThatThrownBy(() -> stateMachine.requireTransition(
                AttemptState.CREATED,
                AttemptState.SUCCEEDED
        )).isInstanceOf(AttemptStateTransitionException.class)
                .hasMessageContaining("CREATED -> SUCCEEDED");

        for (AttemptState terminal : EnumSet.allOf(AttemptState.class)) {
            if (!terminal.isTerminal()) {
                continue;
            }
            for (AttemptState target : AttemptState.values()) {
                if (target != terminal) {
                    assertThat(stateMachine.canTransition(terminal, target))
                            .as("%s 终态不能迁移到 %s", terminal, target)
                            .isFalse();
                }
            }
        }
    }

    @Test
    void shouldTreatSameStateAsAnIdempotentReplay() {
        for (AttemptState state : AttemptState.values()) {
            assertThat(stateMachine.canTransition(state, state)).isTrue();
        }
    }

    private Set<AttemptState> legalTargets(AttemptState source) {
        EnumSet<AttemptState> result = EnumSet.noneOf(AttemptState.class);
        for (AttemptState target : AttemptState.values()) {
            if (source != target && stateMachine.canTransition(source, target)) {
                result.add(target);
            }
        }
        return result;
    }
}
