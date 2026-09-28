package io.testforge.dispatcher.stream;

import java.time.Instant;
import java.util.Objects;

/**
 * 进程内 Redis 暂停/恢复闸门。它只抑制无意义的连接风暴，不保存业务状态。
 */
public final class RedisDispatchAvailability {

    private final RedisDispatchPolicy policy;
    private RedisDispatchState state = RedisDispatchState.ACTIVE;
    private Instant retryAt;
    private String lastFailure;

    public RedisDispatchAvailability(RedisDispatchPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    synchronized Permit acquire(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (state == RedisDispatchState.ACTIVE) {
            return new Permit(true, false, null, null);
        }
        if (state == RedisDispatchState.PROBING) {
            return new Permit(false, false, retryAt, "Redis 恢复探针正在执行");
        }
        if (now.isBefore(retryAt)) {
            return new Permit(false, false, retryAt, lastFailure);
        }
        state = RedisDispatchState.PROBING;
        return new Permit(true, true, retryAt, lastFailure);
    }

    synchronized boolean markAvailable() {
        boolean recovered = state == RedisDispatchState.PROBING || state == RedisDispatchState.PAUSED;
        state = RedisDispatchState.ACTIVE;
        retryAt = null;
        lastFailure = null;
        return recovered;
    }

    synchronized Instant pause(Instant now, String failure) {
        Objects.requireNonNull(now, "now must not be null");
        state = RedisDispatchState.PAUSED;
        retryAt = now.plus(policy.pauseDuration());
        lastFailure = failure;
        return retryAt;
    }

    public synchronized RedisDispatchAvailabilitySnapshot snapshot() {
        return new RedisDispatchAvailabilitySnapshot(state, retryAt, lastFailure);
    }

    record Permit(boolean allowed, boolean recoveryProbe, Instant retryAt, String detail) {
    }
}
