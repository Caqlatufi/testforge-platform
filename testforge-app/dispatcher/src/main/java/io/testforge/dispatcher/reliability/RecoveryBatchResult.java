package io.testforge.dispatcher.reliability;

import io.testforge.dispatcher.reliability.lease.LeaseReapResult;

import java.util.Objects;

public record RecoveryBatchResult(
        LeaseReapResult leaseReap,
        int lostConverged,
        int timedOutConverged
) {
    public RecoveryBatchResult {
        Objects.requireNonNull(leaseReap, "leaseReap 不能为空");
        if (lostConverged < 0 || timedOutConverged < 0) {
            throw new IllegalArgumentException("收敛数量不能小于 0");
        }
    }
}
