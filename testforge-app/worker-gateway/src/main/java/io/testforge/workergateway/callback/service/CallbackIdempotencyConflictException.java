package io.testforge.workergateway.callback.service;

import java.util.UUID;

public class CallbackIdempotencyConflictException extends RuntimeException {

    private final UUID attemptId;
    private final UUID callbackKey;

    public CallbackIdempotencyConflictException(UUID attemptId, UUID callbackKey) {
        super("callbackKey 已被同一 Attempt 的不同载荷使用");
        this.attemptId = attemptId;
        this.callbackKey = callbackKey;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public UUID getCallbackKey() {
        return callbackKey;
    }
}
