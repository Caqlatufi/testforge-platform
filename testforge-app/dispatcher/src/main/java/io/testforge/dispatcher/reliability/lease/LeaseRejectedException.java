package io.testforge.dispatcher.reliability.lease;

import java.util.UUID;

/** token、Worker、状态或租约时间任一条件失效时统一拒绝，避免泄露当前有效凭证。 */
public class LeaseRejectedException extends RuntimeException {

    private final UUID attemptId;

    public LeaseRejectedException(UUID attemptId) {
        super("Attempt 租约已失效或已被替代: " + attemptId);
        this.attemptId = attemptId;
    }

    public UUID getAttemptId() {
        return attemptId;
    }
}
