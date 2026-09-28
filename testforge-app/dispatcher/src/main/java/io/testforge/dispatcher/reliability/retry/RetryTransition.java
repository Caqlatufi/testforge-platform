package io.testforge.dispatcher.reliability.retry;

import io.testforge.runorchestrator.model.attempt.AttemptState;
import io.testforge.runorchestrator.task.model.TaskState;

import java.time.Instant;
import java.util.Objects;

/**
 * 状态存储必须原子提交本对象：Attempt 收敛、Task 终结/重排和 retryAt 不可拆开。
 */
public record RetryTransition(
        RetryAction action,
        TaskState taskState,
        AttemptState attemptState,
        Instant transitionedAt,
        Instant retryAt,
        String reason
) {

    public RetryTransition {
        Objects.requireNonNull(action, "action 不能为空");
        Objects.requireNonNull(taskState, "taskState 不能为空");
        Objects.requireNonNull(attemptState, "attemptState 不能为空");
        Objects.requireNonNull(transitionedAt, "transitionedAt 不能为空");
        if (!attemptState.isTerminal()) {
            throw new IllegalArgumentException("Attempt 目标状态必须是终态");
        }
        if (action == RetryAction.REQUEUE && retryAt == null) {
            throw new IllegalArgumentException("重新排队必须提供 retryAt");
        }
        if (action != RetryAction.REQUEUE && retryAt != null) {
            throw new IllegalArgumentException("非重排操作不能提供 retryAt");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        switch (action) {
            case COMPLETE -> requireStates(
                    taskState == TaskState.SUCCEEDED && attemptState == AttemptState.SUCCEEDED,
                    "完成操作必须收敛为 SUCCEEDED"
            );
            case REQUEUE -> requireStates(taskState == TaskState.QUEUED, "重排操作必须把 Task 置为 QUEUED");
            case TERMINATE -> requireStates(taskState.isTerminal(), "终止操作必须把 Task 置为终态");
            case CANCEL -> requireStates(
                    taskState == TaskState.CANCELLED && attemptState.isTerminal(),
                    "取消操作必须把 Task 收敛为 CANCELLED，并保留 Attempt 已有终态"
            );
            case IGNORE -> throw new IllegalArgumentException("IGNORE 不能写入状态存储");
        }
    }

    private static void requireStates(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
