package io.testforge.workergateway.callback.service;

import io.testforge.workergateway.callback.model.CallbackStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AttemptExecutionGateway {

    Optional<AttemptLeaseSnapshot> renew(
            UUID attemptId,
            String workerId,
            UUID leaseToken,
            Instant acceptedAt,
            Instant extendedUntil
    );

    AttemptCompletion complete(
            UUID attemptId,
            String workerId,
            UUID leaseToken,
            CallbackStatus status,
            Instant acceptedAt,
            Instant extendedUntil
    );
}
