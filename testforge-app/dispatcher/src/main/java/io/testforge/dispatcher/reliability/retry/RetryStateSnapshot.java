package io.testforge.dispatcher.reliability.retry;

import io.testforge.runorchestrator.model.attempt.AttemptState;
import io.testforge.runorchestrator.task.model.TaskState;

import java.util.Objects;
import java.util.UUID;

/** MySQL 中用于一次 CAS 仲裁的 Task/当前 Attempt 快照。 */
public record RetryStateSnapshot(
        UUID taskId,
        TaskState taskState,
        long taskVersion,
        UUID activeAttemptId,
        int activeAttemptNo,
        AttemptState attemptState,
        long attemptVersion,
        boolean cancellationRequested
) {

    public RetryStateSnapshot {
        Objects.requireNonNull(taskId, "taskId 不能为空");
        Objects.requireNonNull(taskState, "taskState 不能为空");
        if (taskVersion < 0) {
            throw new IllegalArgumentException("taskVersion 不能小于 0");
        }
        if ((activeAttemptId == null) != (attemptState == null)) {
            throw new IllegalArgumentException("activeAttemptId 与 attemptState 必须同时存在或同时为空");
        }
        if (activeAttemptId == null) {
            if (activeAttemptNo != 0 || attemptVersion != 0) {
                throw new IllegalArgumentException("无活动 Attempt 时编号和版本必须为 0");
            }
        } else {
            if (activeAttemptNo < 1) {
                throw new IllegalArgumentException("activeAttemptNo 必须大于等于 1");
            }
            if (attemptVersion < 0) {
                throw new IllegalArgumentException("attemptVersion 不能小于 0");
            }
        }
    }
}
