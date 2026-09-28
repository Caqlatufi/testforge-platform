package io.testforge.dispatcher.reliability.lease;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Attempt 租约的 MySQL 持久化边界。
 *
 * <p>实现必须把 Attempt 状态、token、leaseUntil 和 version 作为同一条数据库记录的条件更新依据：
 * Redis 不能成为租约唯一真相源。所有返回 {@code false}/{@code Optional.empty()} 的方法都表示
 * 条件已经失效，调用方不得再凭内存快照补写。</p>
 */
public interface AttemptLeaseStore {

    /**
     * 首次签发租约。同一 Attempt 只能成功一次；重试必须使用新的 Attempt ID。
     */
    boolean issue(LeaseSnapshot initialLease, Instant issuedAt);

    /**
     * 原子校验 Attempt 仍可执行、worker/token 匹配且租约尚未过期，然后延长租约并增加版本。
     * 边界时刻 {@code currentLeaseUntil == acceptedAt} 允许与 Reaper 通过版本条件竞争，最终只能一个成功。
     */
    Optional<LeaseSnapshot> heartbeat(
            UUID attemptId,
            String workerId,
            UUID leaseToken,
            Instant acceptedAt,
            Instant extendedUntil
    );

    /**
     * 从持久化状态读取到期候选。结果只是快照，不能直接视为回收成功。
     */
    List<LeaseSnapshot> findExpired(Instant expiredAtOrBefore, int limit);

    /**
     * 以 Attempt ID、token、version、活动状态和 leaseUntil 条件原子标记 Attempt 为 LOST。
     */
    boolean markLostIfExpired(LeaseSnapshot candidate, Instant detectedAt);
}
