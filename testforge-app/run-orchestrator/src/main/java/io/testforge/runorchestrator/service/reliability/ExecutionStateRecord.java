package io.testforge.runorchestrator.service.reliability;

import io.testforge.runorchestrator.model.attempt.AttemptState;
import io.testforge.runorchestrator.task.model.TaskState;

import java.util.Objects;
import java.util.UUID;

/** 一次完成/失败/取消/超时 CAS 所需的数据库真相快照。 */
public record ExecutionStateRecord(
        UUID taskId,
        TaskState taskState,
        long taskVersion,
        UUID attemptId,
        int attemptNo,
        AttemptState attemptState,
        long attemptVersion,
        boolean cancellationRequested
) {
    public ExecutionStateRecord {
        Objects.requireNonNull(taskId, "taskId 不能为空");
        Objects.requireNonNull(taskState, "taskState 不能为空");
        Objects.requireNonNull(attemptId, "attemptId 不能为空");
        Objects.requireNonNull(attemptState, "attemptState 不能为空");
        if (taskVersion < 0 || attemptVersion < 0 || attemptNo < 1) {
            throw new IllegalArgumentException("版本不能小于 0，attemptNo 必须大于 0");
        }
    }
}
