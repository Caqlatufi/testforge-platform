package io.testforge.dispatcher.reliability.lease;

import java.util.UUID;

/** 同一 Attempt 已有租约时拒绝二次签发。新一轮执行必须创建新的 Attempt。 */
public class LeaseIssueConflictException extends RuntimeException {

    private final UUID attemptId;

    public LeaseIssueConflictException(UUID attemptId) {
        super("Attempt 已存在租约，不能重复签发: " + attemptId);
        this.attemptId = attemptId;
    }

    public UUID getAttemptId() {
        return attemptId;
    }
}
