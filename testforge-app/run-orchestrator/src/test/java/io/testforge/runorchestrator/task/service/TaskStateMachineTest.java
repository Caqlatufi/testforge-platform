package io.testforge.runorchestrator.task.service;

import io.testforge.runorchestrator.task.model.TaskState;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskStateMachineTest {

    private final TaskStateMachine stateMachine = new TaskStateMachine();

    @Test
    void shouldCoverDependencyDispatchExecutionRetryAndCancellationPaths() {
        assertThat(legalTargets(TaskState.CREATED)).containsExactlyInAnyOrder(
                TaskState.WAITING_DEPLOYMENT, TaskState.WAITING_DEPENDENCY,
                TaskState.QUEUED, TaskState.CANCELLED
        );
        assertThat(legalTargets(TaskState.WAITING_DEPLOYMENT)).containsExactlyInAnyOrder(
                TaskState.WAITING_DEPENDENCY, TaskState.QUEUED, TaskState.BLOCKED, TaskState.CANCELLED
        );
        assertThat(legalTargets(TaskState.WAITING_DEPENDENCY)).containsExactlyInAnyOrder(
                TaskState.WAITING_DEPLOYMENT, TaskState.QUEUED, TaskState.BLOCKED, TaskState.CANCELLED
        );
        assertThat(legalTargets(TaskState.QUEUED)).containsExactlyInAnyOrder(
                TaskState.DISPATCHED, TaskState.CANCELLED
        );
        assertThat(legalTargets(TaskState.DISPATCHED)).containsExactlyInAnyOrder(
                TaskState.RUNNING, TaskState.QUEUED, TaskState.CANCELLED
        );
        assertThat(legalTargets(TaskState.RUNNING)).containsExactlyInAnyOrder(
                TaskState.QUEUED,
                TaskState.SUCCEEDED,
                TaskState.FAILED,
                TaskState.TIMEOUT,
                TaskState.CANCELLED
        );
        assertThat(legalTargets(TaskState.TIMEOUT)).containsExactlyInAnyOrder(
                TaskState.QUEUED, TaskState.CANCELLED
        );
    }

    @Test
    void shouldRejectSkippingDispatchAndProtectFinalStates() {
        assertThatThrownBy(() -> stateMachine.requireTransition(TaskState.QUEUED, TaskState.RUNNING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUEUED -> RUNNING");

        for (TaskState terminal : EnumSet.of(
                TaskState.SUCCEEDED,
                TaskState.FAILED,
                TaskState.BLOCKED,
                TaskState.CANCELLED
        )) {
            for (TaskState target : TaskState.values()) {
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
        for (TaskState state : TaskState.values()) {
            assertThat(stateMachine.canTransition(state, state)).isTrue();
        }
    }

    private EnumSet<TaskState> legalTargets(TaskState source) {
        EnumSet<TaskState> result = EnumSet.noneOf(TaskState.class);
        for (TaskState target : TaskState.values()) {
            if (source != target && stateMachine.canTransition(source, target)) {
                result.add(target);
            }
        }
        return result;
    }
}
