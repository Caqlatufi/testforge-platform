package io.testforge.dispatcher.reliability.lease;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 从 MySQL 扫描过期 Attempt，并用候选快照中的 token/version 原子标记 LOST。
 *
 * <p>本类不保存扫描游标，所以进程重启后会重新查询数据库；已被回收或已收到心跳的候选会在
 * 条件更新阶段自然失败。成功结果只报告 LOST 事实，不决定是否重试及何时重新派发。</p>
 */
public class LeaseReaper {

    private final AttemptLeaseStore store;
    private final LeasePolicy policy;
    private final Clock clock;

    public LeaseReaper(AttemptLeaseStore store) {
        this(store, LeasePolicy.defaults(), Clock.systemUTC());
    }

    public LeaseReaper(AttemptLeaseStore store, LeasePolicy policy, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public LeaseReapResult reapExpired() {
        Instant scannedAt = clock.instant();
        List<LeaseSnapshot> candidates = List.copyOf(
                store.findExpired(scannedAt, policy.reaperBatchSize())
        );
        List<LostLease> recovered = new ArrayList<>(candidates.size());
        for (LeaseSnapshot candidate : candidates) {
            if (store.markLostIfExpired(candidate, scannedAt)) {
                recovered.add(new LostLease(
                        candidate.attemptId(),
                        candidate.taskId(),
                        candidate.workerId(),
                        candidate.leaseUntil(),
                        scannedAt
                ));
            }
        }
        return new LeaseReapResult(scannedAt, candidates.size(), recovered);
    }
}
