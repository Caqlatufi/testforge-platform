package io.testforge.dispatcher.reliability.retry;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 有界指数退避策略。maxAttempts 表示包含首次执行在内的总尝试次数。
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须大于等于 1");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff 不能为空");
        Objects.requireNonNull(maxBackoff, "maxBackoff 不能为空");
        if (initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("initialBackoff 必须大于 0");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff 不能小于 initialBackoff");
        }
    }

    public boolean canRetry(int completedAttemptNo) {
        requireAttemptNo(completedAttemptNo);
        return completedAttemptNo < maxAttempts;
    }

    /** attemptNo=1 失败后的首次重试使用 initialBackoff，随后按 2 倍增长并封顶。 */
    public Duration backoffAfter(int completedAttemptNo) {
        requireAttemptNo(completedAttemptNo);
        Duration delay = initialBackoff;
        for (int exponent = 1; exponent < completedAttemptNo; exponent++) {
            if (delay.compareTo(maxBackoff) >= 0
                    || delay.compareTo(maxBackoff.dividedBy(2)) > 0) {
                return maxBackoff;
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    public Instant retryAt(int completedAttemptNo, Instant failedAt) {
        Objects.requireNonNull(failedAt, "failedAt 不能为空");
        return failedAt.plus(backoffAfter(completedAttemptNo));
    }

    private static void requireAttemptNo(int attemptNo) {
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 必须大于等于 1");
        }
    }
}
