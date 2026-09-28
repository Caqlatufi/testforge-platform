package io.testforge.workergateway.callback.service;

import java.util.UUID;

public class AttemptLeaseExpiredException extends RuntimeException {

    private final UUID attemptId;

    public AttemptLeaseExpiredException(UUID attemptId) {
        super("Attempt 租约无效、已过期或已被替代");
        this.attemptId = attemptId;
    }

    public UUID getAttemptId() {
        return attemptId;
    }
}
