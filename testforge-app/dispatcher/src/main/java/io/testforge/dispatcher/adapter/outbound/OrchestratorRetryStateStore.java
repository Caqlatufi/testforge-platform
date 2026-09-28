package io.testforge.dispatcher.adapter.outbound;

import io.testforge.dispatcher.reliability.retry.RetryStateSnapshot;
import io.testforge.dispatcher.reliability.retry.RetryStateStore;
import io.testforge.dispatcher.reliability.retry.RetryTransition;
import io.testforge.runorchestrator.service.reliability.ExecutionReliabilityService;
import io.testforge.runorchestrator.service.reliability.ExecutionStateRecord;
import io.testforge.runorchestrator.service.reliability.ExecutionTransitionCommand;
import io.testforge.runorchestrator.service.reliability.ReliabilityStateConflictException;

import java.util.Objects;
import java.util.UUID;

/** 把 dispatcher 的重试决策映射为 run-orchestrator 的双版本事务 CAS。 */
public final class OrchestratorRetryStateStore implements RetryStateStore {

    private final ExecutionReliabilityService reliabilityService;

    public OrchestratorRetryStateStore(ExecutionReliabilityService reliabilityService) {
        this.reliabilityService = Objects.requireNonNull(reliabilityService, "reliabilityService 不能为空");
    }

    @Override
    public RetryStateSnapshot load(UUID taskId) {
        ExecutionStateRecord state = reliabilityService.load(taskId);
        return new RetryStateSnapshot(
                state.taskId(),
                state.taskState(),
                state.taskVersion(),
                state.attemptId(),
                state.attemptNo(),
                state.attemptState(),
                state.attemptVersion(),
                state.cancellationRequested()
        );
    }

    @Override
    public boolean compareAndSet(RetryStateSnapshot expected, RetryTransition transition) {
        try {
            return reliabilityService.compareAndSet(
                    new ExecutionStateRecord(
                            expected.taskId(),
                            expected.taskState(),
                            expected.taskVersion(),
                            expected.activeAttemptId(),
                            expected.activeAttemptNo(),
                            expected.attemptState(),
                            expected.attemptVersion(),
                            expected.cancellationRequested()
                    ),
                    new ExecutionTransitionCommand(
                            transition.taskState(),
                            transition.attemptState(),
                            transition.transitionedAt(),
                            transition.retryAt(),
                            transition.reason()
                    )
            );
        } catch (ReliabilityStateConflictException ignored) {
            return false;
        }
    }
}
