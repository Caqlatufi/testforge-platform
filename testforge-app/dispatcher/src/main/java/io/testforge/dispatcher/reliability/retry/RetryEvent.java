package io.testforge.dispatcher.reliability.retry;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 一次可能与取消、完成或超时并发的 Attempt 结果事件。 */
public record RetryEvent(
        UUID taskId,
        UUID attemptId,
        int attemptNo,
        RetryEventType type,
        String failureType,
        Instant occurredAt
) {

    public RetryEvent {
        Objects.requireNonNull(taskId, "taskId 不能为空");
        Objects.requireNonNull(attemptId, "attemptId 不能为空");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 必须大于等于 1");
        }
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        if (type == RetryEventType.FAILED && (failureType == null || failureType.isBlank())) {
            throw new IllegalArgumentException("FAILED 事件必须提供 failureType");
        }
        if (type != RetryEventType.FAILED && failureType != null) {
            throw new IllegalArgumentException("只有 FAILED 事件可以提供 failureType");
        }
    }

    public static RetryEvent completed(UUID taskId, UUID attemptId, int attemptNo, Instant at) {
        return new RetryEvent(taskId, attemptId, attemptNo, RetryEventType.COMPLETED, null, at);
    }

    public static RetryEvent failed(
            UUID taskId,
            UUID attemptId,
            int attemptNo,
            String failureType,
            Instant at
    ) {
        return new RetryEvent(taskId, attemptId, attemptNo, RetryEventType.FAILED, failureType, at);
    }

    public static RetryEvent timedOut(UUID taskId, UUID attemptId, int attemptNo, Instant at) {
        return new RetryEvent(taskId, attemptId, attemptNo, RetryEventType.TIMED_OUT, null, at);
    }

    public static RetryEvent cancelled(UUID taskId, UUID attemptId, int attemptNo, Instant at) {
        return new RetryEvent(taskId, attemptId, attemptNo, RetryEventType.CANCELLED, null, at);
    }
}
