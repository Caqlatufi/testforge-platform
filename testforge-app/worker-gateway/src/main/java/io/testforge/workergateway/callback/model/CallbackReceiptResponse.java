package io.testforge.workergateway.callback.model;

import java.time.Instant;
import java.util.UUID;

public record CallbackReceiptResponse(
        UUID attemptId,
        UUID callbackKey,
        CallbackDisposition disposition,
        CallbackDisposition originalDisposition,
        UUID taskId,
        CallbackStatus resultStatus,
        String reason,
        Instant receivedAt
) {
}
