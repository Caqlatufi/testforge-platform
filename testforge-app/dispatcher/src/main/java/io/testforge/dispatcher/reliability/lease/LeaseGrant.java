package io.testforge.dispatcher.reliability.lease;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Worker 领取 Attempt 后得到的租约凭证。 */
public record LeaseGrant(
        UUID attemptId,
        UUID taskId,
        String workerId,
        UUID leaseToken,
        Instant issuedAt,
        Instant leaseUntil,
        Duration heartbeatInterval
) {

    public LeaseGrant {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        Objects.requireNonNull(heartbeatInterval, "heartbeatInterval must not be null");
        if (!leaseUntil.isAfter(issuedAt)) {
            throw new IllegalArgumentException("leaseUntil must be after issuedAt");
        }
    }

    LeaseSnapshot toInitialSnapshot() {
        return new LeaseSnapshot(
                attemptId,
                taskId,
                workerId,
                leaseToken,
                leaseUntil,
                0
        );
    }
}
