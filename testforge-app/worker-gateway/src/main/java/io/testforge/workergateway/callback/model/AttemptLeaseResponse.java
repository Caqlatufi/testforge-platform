package io.testforge.workergateway.callback.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AttemptLeaseResponse(
        UUID attemptId,
        String state,
        Instant leaseUntil,
        BigDecimal progress,
        Instant acceptedAt
) {
}
