package io.testforge.workergateway.callback.service;

import io.testforge.runorchestrator.model.attempt.AttemptState;
import io.testforge.runorchestrator.service.reliability.AttemptLeaseRecord;
import io.testforge.runorchestrator.service.reliability.ExecutionReliabilityService;
import io.testforge.runorchestrator.service.reliability.ExecutionStateRecord;
import io.testforge.runorchestrator.service.reliability.ExecutionTransitionCommand;
import io.testforge.runorchestrator.service.reliability.ReliabilityStateConflictException;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.workergateway.callback.model.CallbackStatus;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 仅通过 run-orchestrator 公开服务推进状态，不访问其 Repository。 */
public class OrchestratorAttemptExecutionGateway implements AttemptExecutionGateway {

    private final ObjectProvider<ExecutionReliabilityService> reliabilityServiceProvider;

    public OrchestratorAttemptExecutionGateway(
            ObjectProvider<ExecutionReliabilityService> reliabilityServiceProvider
    ) {
        this.reliabilityServiceProvider = Objects.requireNonNull(
                reliabilityServiceProvider, "reliabilityServiceProvider 不能为空"
        );
    }

    @Override
    public Optional<AttemptLeaseSnapshot> renew(
            UUID attemptId,
            String workerId,
            UUID leaseToken,
            Instant acceptedAt,
            Instant extendedUntil
    ) {
        return service().heartbeat(attemptId, workerId, leaseToken, acceptedAt, extendedUntil)
                .map(this::toSnapshot);
    }

    @Override
    public AttemptCompletion complete(
            UUID attemptId,
            String workerId,
            UUID leaseToken,
            CallbackStatus status,
            Instant acceptedAt,
            Instant extendedUntil
    ) {
        ExecutionReliabilityService service = service();
        Optional<AttemptLeaseRecord> renewed = service.heartbeat(
                attemptId, workerId, leaseToken, acceptedAt, extendedUntil
        );
        if (renewed.isEmpty()) {
            return AttemptCompletion.stale();
        }

        ExecutionStateRecord current;
        try {
            current = service.load(renewed.get().taskId());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return AttemptCompletion.stale();
        }
        if (!current.attemptId().equals(attemptId)
                || current.attemptState() != AttemptState.RUNNING
                || current.taskState() != TaskState.RUNNING) {
            return AttemptCompletion.stale();
        }

        CallbackStatus effectiveStatus = current.cancellationRequested()
                ? CallbackStatus.CANCELLED
                : status;
        AttemptState attemptTarget = attemptTarget(effectiveStatus);
        TaskState taskTarget = taskTarget(effectiveStatus);
        try {
            boolean changed = service.compareAndSet(
                    current,
                    new ExecutionTransitionCommand(
                            taskTarget,
                            attemptTarget,
                            acceptedAt,
                            null,
                            "WORKER_CALLBACK_" + effectiveStatus.name()
                    )
            );
            return changed
                    ? AttemptCompletion.accepted(current.taskId(), effectiveStatus)
                    : AttemptCompletion.stale();
        } catch (ReliabilityStateConflictException | IllegalStateException exception) {
            return AttemptCompletion.stale();
        }
    }

    private ExecutionReliabilityService service() {
        ExecutionReliabilityService service = reliabilityServiceProvider.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("Attempt callback 尚未接入 run-orchestrator");
        }
        return service;
    }

    private AttemptLeaseSnapshot toSnapshot(AttemptLeaseRecord lease) {
        return new AttemptLeaseSnapshot(lease.attemptId(), lease.taskId(), lease.leaseUntil());
    }

    private AttemptState attemptTarget(CallbackStatus status) {
        return switch (status) {
            case PASSED -> AttemptState.SUCCEEDED;
            case ASSERTION_FAILED, INFRA_FAILED -> AttemptState.FAILED;
            case CANCELLED -> AttemptState.CANCELLED;
        };
    }

    private TaskState taskTarget(CallbackStatus status) {
        return switch (status) {
            case PASSED -> TaskState.SUCCEEDED;
            case ASSERTION_FAILED, INFRA_FAILED -> TaskState.FAILED;
            case CANCELLED -> TaskState.CANCELLED;
        };
    }
}
