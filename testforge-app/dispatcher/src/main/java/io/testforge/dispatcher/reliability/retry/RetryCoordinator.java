package io.testforge.dispatcher.reliability.retry;

import io.testforge.runorchestrator.model.attempt.AttemptState;
import io.testforge.runorchestrator.task.model.TaskState;

import java.time.Instant;
import java.util.Objects;

/**
 * 在至少一次事件语义下仲裁完成、失败、取消与超时。
 *
 * <p>并发事件只通过状态存储的 Task/Attempt 双版本 CAS 竞争。失败方重新读取真相；
 * 一旦发现 Task 或 Attempt 已终结，就返回已收敛而不是覆盖先到的结果。</p>
 */
public final class RetryCoordinator {

    private static final int DEFAULT_MAX_CAS_ATTEMPTS = 8;

    private final RetryStateStore stateStore;
    private final RetryFailureClassifier failureClassifier;
    private final RetryPolicy retryPolicy;
    private final int maxCasAttempts;

    public RetryCoordinator(RetryStateStore stateStore, RetryPolicy retryPolicy) {
        this(stateStore, new RetryFailureClassifier(), retryPolicy, DEFAULT_MAX_CAS_ATTEMPTS);
    }

    RetryCoordinator(
            RetryStateStore stateStore,
            RetryFailureClassifier failureClassifier,
            RetryPolicy retryPolicy,
            int maxCasAttempts
    ) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore 不能为空");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier 不能为空");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy 不能为空");
        if (maxCasAttempts < 1) {
            throw new IllegalArgumentException("maxCasAttempts 必须大于等于 1");
        }
        this.maxCasAttempts = maxCasAttempts;
    }

    public RetryConvergenceResult converge(RetryEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        for (int casAttempt = 1; casAttempt <= maxCasAttempts; casAttempt++) {
            RetryStateSnapshot snapshot = Objects.requireNonNull(
                    stateStore.load(event.taskId()),
                    "stateStore.load 不能返回 null"
            );
            if (!snapshot.taskId().equals(event.taskId())) {
                throw new IllegalStateException("状态存储返回了错误的 taskId");
            }

            RetryConvergenceResult ignored = ignoredResult(snapshot, event);
            if (ignored != null) {
                return ignored;
            }

            RetryTransition transition = decide(snapshot, event);
            if (stateStore.compareAndSet(snapshot, transition)) {
                return applied(transition);
            }
        }
        throw new RetryContentionException(event.taskId(), maxCasAttempts);
    }

    private RetryConvergenceResult ignoredResult(RetryStateSnapshot snapshot, RetryEvent event) {
        if (snapshot.taskState().isTerminal()) {
            return ignored(
                    RetryConvergenceOutcome.ALREADY_CONVERGED,
                    snapshot,
                    "Task 已处于终态，忽略后到的 " + event.type()
            );
        }
        if (snapshot.activeAttemptId() == null
                || !snapshot.activeAttemptId().equals(event.attemptId())
                || snapshot.activeAttemptNo() != event.attemptNo()) {
            return ignored(
                    RetryConvergenceOutcome.STALE_ATTEMPT,
                    snapshot,
                    "事件不属于当前有效 Attempt"
            );
        }
        boolean recoveringPersistedLost = snapshot.attemptState() == AttemptState.LOST
                && event.type() == RetryEventType.FAILED
                && failureClassifier.classify(event.failureType()) == RetryFailureType.WORKER_LOST;
        if (snapshot.attemptState().isTerminal()
                && !recoveringPersistedLost
                && !snapshot.cancellationRequested()) {
            return ignored(
                    RetryConvergenceOutcome.ALREADY_CONVERGED,
                    snapshot,
                    "Attempt 已处于终态，忽略重复或迟到事件"
            );
        }
        boolean cancellable = snapshot.taskState() == TaskState.DISPATCHED
                || snapshot.taskState() == TaskState.RUNNING;
        if (!cancellable) {
            return ignored(
                    RetryConvergenceOutcome.ALREADY_CONVERGED,
                    snapshot,
                    "Task 当前状态不能由 Attempt 结果推进: " + snapshot.taskState()
            );
        }
        if (snapshot.cancellationRequested() || event.type() == RetryEventType.CANCELLED) {
            return null;
        }
        if (snapshot.taskState() != TaskState.RUNNING
                || (snapshot.attemptState() != AttemptState.RUNNING && !recoveringPersistedLost)) {
            return ignored(
                    RetryConvergenceOutcome.ALREADY_CONVERGED,
                    snapshot,
                    "完成、失败和超时只接受 RUNNING Attempt"
            );
        }
        return null;
    }

    private RetryTransition decide(RetryStateSnapshot snapshot, RetryEvent event) {
        if (snapshot.cancellationRequested() || event.type() == RetryEventType.CANCELLED) {
            return new RetryTransition(
                    RetryAction.CANCEL,
                    TaskState.CANCELLED,
                    snapshot.attemptState().isTerminal()
                            ? snapshot.attemptState()
                            : AttemptState.CANCELLED,
                    event.occurredAt(),
                    null,
                    snapshot.cancellationRequested() ? "取消请求先于结果收敛" : "Worker 确认取消"
            );
        }
        if (event.type() == RetryEventType.COMPLETED) {
            return new RetryTransition(
                    RetryAction.COMPLETE,
                    TaskState.SUCCEEDED,
                    AttemptState.SUCCEEDED,
                    event.occurredAt(),
                    null,
                    "Attempt 执行成功"
            );
        }

        RetryFailureType failureType = event.type() == RetryEventType.TIMED_OUT
                ? RetryFailureType.TIMEOUT
                : failureClassifier.classify(event.failureType());
        AttemptState attemptTarget = failureType == RetryFailureType.TIMEOUT
                ? AttemptState.TIMEOUT
                : failureType == RetryFailureType.WORKER_LOST
                ? AttemptState.LOST
                : AttemptState.FAILED;

        if (failureType.isRetryable() && retryPolicy.canRetry(event.attemptNo())) {
            Instant retryAt = retryPolicy.retryAt(event.attemptNo(), event.occurredAt());
            return new RetryTransition(
                    RetryAction.REQUEUE,
                    TaskState.QUEUED,
                    attemptTarget,
                    event.occurredAt(),
                    retryAt,
                    failureType + " 可重试，第 " + (event.attemptNo() + 1) + " 次尝试不早于 " + retryAt
            );
        }

        TaskState taskTarget = failureType == RetryFailureType.TIMEOUT
                ? TaskState.TIMEOUT
                : TaskState.FAILED;
        String reason = failureType.isRetryable()
                ? failureType + " 已达到最大尝试次数 " + retryPolicy.maxAttempts()
                : failureType + " 默认不自动重试";
        return new RetryTransition(
                RetryAction.TERMINATE,
                taskTarget,
                attemptTarget,
                event.occurredAt(),
                null,
                reason
        );
    }

    private static RetryConvergenceResult applied(RetryTransition transition) {
        return new RetryConvergenceResult(
                RetryConvergenceOutcome.APPLIED,
                transition.action(),
                transition.taskState(),
                transition.attemptState(),
                transition.retryAt(),
                transition.reason()
        );
    }

    private static RetryConvergenceResult ignored(
            RetryConvergenceOutcome outcome,
            RetryStateSnapshot snapshot,
            String reason
    ) {
        return new RetryConvergenceResult(
                outcome,
                RetryAction.IGNORE,
                snapshot.taskState(),
                snapshot.attemptState(),
                null,
                reason
        );
    }
}
