package io.testforge.workergateway.callback.service;

import io.testforge.workergateway.callback.model.CallbackStatus;

import java.util.UUID;

public record AttemptCompletion(
        boolean accepted,
        UUID taskId,
        CallbackStatus effectiveStatus
) {
    public static AttemptCompletion stale() {
        return new AttemptCompletion(false, null, null);
    }

    public static AttemptCompletion accepted(UUID taskId, CallbackStatus effectiveStatus) {
        return new AttemptCompletion(true, taskId, effectiveStatus);
    }
}
