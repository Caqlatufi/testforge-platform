package io.testforge.dispatcher.reliability;

import io.testforge.dispatcher.reliability.lease.LeaseReapResult;
import io.testforge.dispatcher.reliability.lease.LeaseReaper;
import io.testforge.dispatcher.reliability.retry.RetryConvergenceOutcome;
import io.testforge.dispatcher.reliability.retry.RetryCoordinator;
import io.testforge.dispatcher.reliability.retry.RetryEvent;

import java.time.Clock;
import java.util.Objects;

/** 一轮扫描同时处理租约失联补偿和 Task 硬超时。 */
public final class AttemptRecoveryService {

    private final LeaseReaper leaseReaper;
    private final RecoveryEventSource eventSource;
    private final RetryCoordinator retryCoordinator;
    private final Clock clock;
    private final int batchSize;

    public AttemptRecoveryService(
            LeaseReaper leaseReaper,
            RecoveryEventSource eventSource,
            RetryCoordinator retryCoordinator,
            Clock clock,
            int batchSize
    ) {
        this.leaseReaper = Objects.requireNonNull(leaseReaper, "leaseReaper 不能为空");
        this.eventSource = Objects.requireNonNull(eventSource, "eventSource 不能为空");
        this.retryCoordinator = Objects.requireNonNull(retryCoordinator, "retryCoordinator 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize 必须大于 0");
        }
        this.batchSize = batchSize;
    }

    public RecoveryBatchResult recoverOnce() {
        LeaseReapResult leaseResult = leaseReaper.reapExpired();
        int lost = converge(eventSource.findLost(batchSize));
        int timedOut = converge(eventSource.findTimedOut(clock.instant(), batchSize));
        return new RecoveryBatchResult(leaseResult, lost, timedOut);
    }

    private int converge(Iterable<RetryEvent> events) {
        int applied = 0;
        for (RetryEvent event : events) {
            if (retryCoordinator.converge(event).outcome() == RetryConvergenceOutcome.APPLIED) {
                applied++;
            }
        }
        return applied;
    }
}
