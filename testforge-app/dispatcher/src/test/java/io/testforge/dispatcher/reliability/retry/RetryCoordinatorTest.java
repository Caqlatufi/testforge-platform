package io.testforge.dispatcher.reliability.retry;

import io.testforge.runorchestrator.model.attempt.AttemptState;
import io.testforge.runorchestrator.task.model.TaskState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryCoordinatorTest {

    private static final UUID TASK_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ATTEMPT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
    private static final RetryPolicy POLICY = new RetryPolicy(
            3,
            Duration.ofSeconds(5),
            Duration.ofMinutes(1)
    );

    @Test
    void shouldTerminateAssertionFailureWithoutRetry() {
        MutableStateStore store = runningStore(1);
        RetryCoordinator coordinator = new RetryCoordinator(store, POLICY);

        RetryConvergenceResult result = coordinator.converge(
                RetryEvent.failed(TASK_ID, ATTEMPT_ID, 1, "ASSERTION_FAILED", NOW)
        );

        assertThat(result.outcome()).isEqualTo(RetryConvergenceOutcome.APPLIED);
        assertThat(result.action()).isEqualTo(RetryAction.TERMINATE);
        assertThat(result.taskState()).isEqualTo(TaskState.FAILED);
        assertThat(result.attemptState()).isEqualTo(AttemptState.FAILED);
        assertThat(result.retryAt()).isNull();
        assertThat(store.writeCount).isEqualTo(1);
    }

    @Test
    void shouldRequeueInfrastructureFailureWithBoundedBackoff() {
        MutableStateStore store = runningStore(1);
        RetryCoordinator coordinator = new RetryCoordinator(store, POLICY);

        RetryConvergenceResult first = coordinator.converge(
                RetryEvent.failed(TASK_ID, ATTEMPT_ID, 1, "NETWORK_ERROR", NOW)
        );

        assertThat(first.action()).isEqualTo(RetryAction.REQUEUE);
        assertThat(first.taskState()).isEqualTo(TaskState.QUEUED);
        assertThat(first.attemptState()).isEqualTo(AttemptState.FAILED);
        assertThat(first.retryAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(store.lastTransition.retryAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void shouldStopInfrastructureRetryAtMaximumAttemptCount() {
        MutableStateStore store = runningStore(3);
        RetryCoordinator coordinator = new RetryCoordinator(store, POLICY);

        RetryConvergenceResult result = coordinator.converge(
                RetryEvent.failed(TASK_ID, ATTEMPT_ID, 3, "WORKER_LOST", NOW)
        );

        assertThat(result.action()).isEqualTo(RetryAction.TERMINATE);
        assertThat(result.taskState()).isEqualTo(TaskState.FAILED);
        assertThat(result.attemptState()).isEqualTo(AttemptState.LOST);
        assertThat(result.reason()).contains("最大尝试次数 3");
    }

    @Test
    void shouldRequeueAnAttemptAlreadyPersistedAsLostByTheLeaseReaper() {
        MutableStateStore store = runningStore(1);
        store.state = new RetryStateSnapshot(
                TASK_ID,
                TaskState.RUNNING,
                10,
                ATTEMPT_ID,
                1,
                AttemptState.LOST,
                21,
                false
        );
        RetryCoordinator coordinator = new RetryCoordinator(store, POLICY);

        RetryConvergenceResult result = coordinator.converge(
                RetryEvent.failed(TASK_ID, ATTEMPT_ID, 1, "WORKER_LOST", NOW)
        );

        assertThat(result.outcome()).isEqualTo(RetryConvergenceOutcome.APPLIED);
        assertThat(result.action()).isEqualTo(RetryAction.REQUEUE);
        assertThat(result.taskState()).isEqualTo(TaskState.QUEUED);
        assertThat(result.attemptState()).isEqualTo(AttemptState.LOST);
    }

    @Test
    void shouldLetCancellationConvergeTaskWhilePreservingPersistedLostAttempt() {
        MutableStateStore store = runningStore(1);
        store.state = new RetryStateSnapshot(
                TASK_ID,
                TaskState.RUNNING,
                10,
                ATTEMPT_ID,
                1,
                AttemptState.LOST,
                21,
                true
        );
        RetryCoordinator coordinator = new RetryCoordinator(store, POLICY);

        RetryConvergenceResult result = coordinator.converge(
                RetryEvent.failed(TASK_ID, ATTEMPT_ID, 1, "WORKER_LOST", NOW)
        );

        assertThat(result.action()).isEqualTo(RetryAction.CANCEL);
        assertThat(result.taskState()).isEqualTo(TaskState.CANCELLED);
        assertThat(result.attemptState()).isEqualTo(AttemptState.LOST);
    }

    @Test
    void shouldRequeueTimeoutAndPreserveTimeoutWhenBudgetIsExhausted() {
        MutableStateStore retryStore = runningStore(2);
        RetryCoordinator retryCoordinator = new RetryCoordinator(retryStore, POLICY);

        RetryConvergenceResult retried = retryCoordinator.converge(
                RetryEvent.timedOut(TASK_ID, ATTEMPT_ID, 2, NOW)
        );

        assertThat(retried.action()).isEqualTo(RetryAction.REQUEUE);
        assertThat(retried.retryAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(retried.attemptState()).isEqualTo(AttemptState.TIMEOUT);

        MutableStateStore exhaustedStore = runningStore(3);
        RetryCoordinator exhaustedCoordinator = new RetryCoordinator(exhaustedStore, POLICY);
        RetryConvergenceResult exhausted = exhaustedCoordinator.converge(
                RetryEvent.timedOut(TASK_ID, ATTEMPT_ID, 3, NOW)
        );

        assertThat(exhausted.action()).isEqualTo(RetryAction.TERMINATE);
        assertThat(exhausted.taskState()).isEqualTo(TaskState.TIMEOUT);
        assertThat(exhausted.attemptState()).isEqualTo(AttemptState.TIMEOUT);
    }

    @Test
    void shouldLetPersistedCancellationBeatConcurrentCompletion() {
        MutableStateStore store = runningStore(1);
        store.state = copy(store.state, true);
        RetryCoordinator coordinator = new RetryCoordinator(store, POLICY);

        RetryConvergenceResult result = coordinator.converge(
                RetryEvent.completed(TASK_ID, ATTEMPT_ID, 1, NOW)
        );

        assertThat(result.action()).isEqualTo(RetryAction.CANCEL);
        assertThat(result.taskState()).isEqualTo(TaskState.CANCELLED);
        assertThat(result.attemptState()).isEqualTo(AttemptState.CANCELLED);
    }

    @Test
    void shouldConvergeAfterCancelWinsTheCasRace() {
        MutableStateStore store = runningStore(1);
        store.onFirstConflict = current -> new RetryStateSnapshot(
                current.taskId(),
                TaskState.CANCELLED,
                current.taskVersion() + 1,
                current.activeAttemptId(),
                current.activeAttemptNo(),
                AttemptState.CANCELLED,
                current.attemptVersion() + 1,
                true
        );
        RetryCoordinator coordinator = new RetryCoordinator(store, POLICY);

        RetryConvergenceResult result = coordinator.converge(
                RetryEvent.completed(TASK_ID, ATTEMPT_ID, 1, NOW)
        );

        assertThat(result.outcome()).isEqualTo(RetryConvergenceOutcome.ALREADY_CONVERGED);
        assertThat(result.action()).isEqualTo(RetryAction.IGNORE);
        assertThat(result.taskState()).isEqualTo(TaskState.CANCELLED);
        assertThat(store.writeCount).isZero();
        assertThat(store.compareCount).isEqualTo(1);
    }

    @Test
    void shouldNeverLetTimeoutOverrideCompletedTerminalState() {
        MutableStateStore store = runningStore(1);
        RetryCoordinator coordinator = new RetryCoordinator(store, POLICY);

        RetryConvergenceResult completed = coordinator.converge(
                RetryEvent.completed(TASK_ID, ATTEMPT_ID, 1, NOW)
        );
        RetryConvergenceResult lateTimeout = coordinator.converge(
                RetryEvent.timedOut(TASK_ID, ATTEMPT_ID, 1, NOW.plusSeconds(1))
        );

        assertThat(completed.taskState()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(lateTimeout.outcome()).isEqualTo(RetryConvergenceOutcome.ALREADY_CONVERGED);
        assertThat(lateTimeout.action()).isEqualTo(RetryAction.IGNORE);
        assertThat(lateTimeout.taskState()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(store.writeCount).isEqualTo(1);
    }

    @Test
    void shouldNeverLetLateCompletionOverrideFinalTimeout() {
        MutableStateStore store = runningStore(3);
        RetryCoordinator coordinator = new RetryCoordinator(store, POLICY);

        RetryConvergenceResult timeout = coordinator.converge(
                RetryEvent.timedOut(TASK_ID, ATTEMPT_ID, 3, NOW)
        );
        RetryConvergenceResult lateCompletion = coordinator.converge(
                RetryEvent.completed(TASK_ID, ATTEMPT_ID, 3, NOW.plusSeconds(1))
        );

        assertThat(timeout.taskState()).isEqualTo(TaskState.TIMEOUT);
        assertThat(lateCompletion.outcome()).isEqualTo(RetryConvergenceOutcome.ALREADY_CONVERGED);
        assertThat(lateCompletion.taskState()).isEqualTo(TaskState.TIMEOUT);
        assertThat(store.writeCount).isEqualTo(1);
    }

    @Test
    void shouldRejectAResultFromAnOldAttemptWithoutWriting() {
        MutableStateStore store = runningStore(2);
        RetryCoordinator coordinator = new RetryCoordinator(store, POLICY);

        RetryConvergenceResult result = coordinator.converge(
                RetryEvent.failed(
                        TASK_ID,
                        UUID.fromString("20000000-0000-0000-0000-000000000099"),
                        1,
                        "INFRA_FAILED",
                        NOW
                )
        );

        assertThat(result.outcome()).isEqualTo(RetryConvergenceOutcome.STALE_ATTEMPT);
        assertThat(result.action()).isEqualTo(RetryAction.IGNORE);
        assertThat(store.compareCount).isZero();
    }

    @Test
    void shouldSurfacePathologicalContentionInsteadOfApplyingWithoutCas() {
        MutableStateStore store = runningStore(1);
        store.alwaysConflict = true;
        RetryCoordinator coordinator = new RetryCoordinator(
                store,
                new RetryFailureClassifier(),
                POLICY,
                3
        );

        assertThatThrownBy(() -> coordinator.converge(
                RetryEvent.failed(TASK_ID, ATTEMPT_ID, 1, "ENVIRONMENT", NOW)
        )).isInstanceOf(RetryContentionException.class)
                .hasMessageContaining("3 次 CAS");
        assertThat(store.writeCount).isZero();
        assertThat(store.compareCount).isEqualTo(3);
    }

    private static MutableStateStore runningStore(int attemptNo) {
        return new MutableStateStore(new RetryStateSnapshot(
                TASK_ID,
                TaskState.RUNNING,
                10,
                ATTEMPT_ID,
                attemptNo,
                AttemptState.RUNNING,
                20,
                false
        ));
    }

    private static RetryStateSnapshot copy(RetryStateSnapshot source, boolean cancellationRequested) {
        return new RetryStateSnapshot(
                source.taskId(),
                source.taskState(),
                source.taskVersion(),
                source.activeAttemptId(),
                source.activeAttemptNo(),
                source.attemptState(),
                source.attemptVersion(),
                cancellationRequested
        );
    }

    private static final class MutableStateStore implements RetryStateStore {
        private RetryStateSnapshot state;
        private RetryTransition lastTransition;
        private int compareCount;
        private int writeCount;
        private boolean alwaysConflict;
        private UnaryOperator<RetryStateSnapshot> onFirstConflict;

        private MutableStateStore(RetryStateSnapshot state) {
            this.state = state;
        }

        @Override
        public RetryStateSnapshot load(UUID taskId) {
            assertThat(taskId).isEqualTo(state.taskId());
            return state;
        }

        @Override
        public boolean compareAndSet(RetryStateSnapshot expectedSnapshot, RetryTransition transition) {
            compareCount++;
            if (onFirstConflict != null) {
                state = onFirstConflict.apply(state);
                onFirstConflict = null;
                return false;
            }
            if (alwaysConflict || !state.equals(expectedSnapshot)) {
                return false;
            }
            lastTransition = transition;
            state = new RetryStateSnapshot(
                    state.taskId(),
                    transition.taskState(),
                    state.taskVersion() + 1,
                    state.activeAttemptId(),
                    state.activeAttemptNo(),
                    transition.attemptState(),
                    state.attemptVersion() + 1,
                    state.cancellationRequested()
            );
            writeCount++;
            return true;
        }
    }
}
