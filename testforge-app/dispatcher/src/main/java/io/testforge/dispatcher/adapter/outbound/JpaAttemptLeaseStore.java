package io.testforge.dispatcher.adapter.outbound;

import io.testforge.dispatcher.reliability.lease.AttemptLeaseStore;
import io.testforge.dispatcher.reliability.lease.LeaseSnapshot;
import io.testforge.runorchestrator.service.reliability.AttemptLeaseRecord;
import io.testforge.runorchestrator.service.reliability.ExecutionReliabilityService;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 通过 run-orchestrator 公开服务把租约保存到 MySQL Attempt 记录。 */
public final class JpaAttemptLeaseStore implements AttemptLeaseStore {

    private final ExecutionReliabilityService reliabilityService;

    public JpaAttemptLeaseStore(ExecutionReliabilityService reliabilityService) {
        this.reliabilityService = Objects.requireNonNull(reliabilityService, "reliabilityService 不能为空");
    }

    @Override
    public boolean issue(LeaseSnapshot initialLease, Instant issuedAt) {
        return reliabilityService.createAttempt(toRecord(initialLease), issuedAt);
    }

    @Override
    public Optional<LeaseSnapshot> heartbeat(
            UUID attemptId,
            String workerId,
            UUID leaseToken,
            Instant acceptedAt,
            Instant extendedUntil
    ) {
        return reliabilityService.heartbeat(
                attemptId, workerId, leaseToken, acceptedAt, extendedUntil
        ).map(this::toSnapshot);
    }

    @Override
    public List<LeaseSnapshot> findExpired(Instant expiredAtOrBefore, int limit) {
        return reliabilityService.findExpired(expiredAtOrBefore, limit)
                .stream()
                .map(this::toSnapshot)
                .toList();
    }

    @Override
    public boolean markLostIfExpired(LeaseSnapshot candidate, Instant detectedAt) {
        return reliabilityService.markLostIfExpired(toRecord(candidate), detectedAt);
    }

    private AttemptLeaseRecord toRecord(LeaseSnapshot snapshot) {
        return new AttemptLeaseRecord(
                snapshot.attemptId(),
                snapshot.taskId(),
                snapshot.workerId(),
                snapshot.leaseToken(),
                snapshot.leaseUntil(),
                snapshot.version()
        );
    }

    private LeaseSnapshot toSnapshot(AttemptLeaseRecord record) {
        return new LeaseSnapshot(
                record.attemptId(),
                record.taskId(),
                record.workerId(),
                record.leaseToken(),
                record.leaseUntil(),
                record.version()
        );
    }
}
