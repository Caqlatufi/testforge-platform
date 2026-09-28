package io.testforge.dispatcher.reliability.lease;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 签发并续期 Attempt 租约；所有有效性判断最终由持久化 CAS 仲裁。 */
public class AttemptLeaseService {

    private final AttemptLeaseStore store;
    private final LeasePolicy policy;
    private final Clock clock;
    private final Supplier<UUID> tokenSupplier;

    public AttemptLeaseService(AttemptLeaseStore store) {
        this(store, LeasePolicy.defaults(), Clock.systemUTC(), UUID::randomUUID);
    }

    public AttemptLeaseService(
            AttemptLeaseStore store,
            LeasePolicy policy,
            Clock clock,
            Supplier<UUID> tokenSupplier
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier must not be null");
    }

    public LeaseGrant issue(UUID attemptId, UUID taskId, String workerId) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }

        Instant issuedAt = clock.instant();
        LeaseGrant grant = new LeaseGrant(
                attemptId,
                taskId,
                workerId,
                Objects.requireNonNull(tokenSupplier.get(), "tokenSupplier returned null"),
                issuedAt,
                issuedAt.plus(policy.leaseDuration()),
                policy.heartbeatInterval()
        );
        if (!store.issue(grant.toInitialSnapshot(), issuedAt)) {
            throw new LeaseIssueConflictException(attemptId);
        }
        return grant;
    }

    public LeaseHeartbeat heartbeat(UUID attemptId, String workerId, UUID leaseToken) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");

        Instant acceptedAt = clock.instant();
        Instant extendedUntil = acceptedAt.plus(policy.leaseDuration());
        LeaseSnapshot renewed = store.heartbeat(
                        attemptId,
                        workerId,
                        leaseToken,
                        acceptedAt,
                        extendedUntil
                )
                .orElseThrow(() -> new LeaseRejectedException(attemptId));
        return new LeaseHeartbeat(
                attemptId,
                acceptedAt,
                acceptedAt.plus(policy.heartbeatInterval()),
                renewed.leaseUntil(),
                renewed.version()
        );
    }
}
