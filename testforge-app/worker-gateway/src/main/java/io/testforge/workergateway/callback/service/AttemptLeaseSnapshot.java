package io.testforge.workergateway.callback.service;

import java.time.Instant;
import java.util.UUID;

public record AttemptLeaseSnapshot(
        UUID attemptId,
        UUID taskId,
        Instant leaseUntil
) {
}
