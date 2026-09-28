package io.testforge.dispatcher.stream;

import java.time.Duration;
import java.util.Objects;

/**
 * Redis 暂停窗口。窗口结束后只放行一个探针，成功即恢复正常派发。
 */
public record RedisDispatchPolicy(Duration pauseDuration) {

    public static final Duration DEFAULT_PAUSE_DURATION = Duration.ofSeconds(5);

    public RedisDispatchPolicy {
        Objects.requireNonNull(pauseDuration, "pauseDuration must not be null");
        if (pauseDuration.isNegative() || pauseDuration.isZero()) {
            throw new IllegalArgumentException("pauseDuration 必须大于 0");
        }
    }

    public static RedisDispatchPolicy defaults() {
        return new RedisDispatchPolicy(DEFAULT_PAUSE_DURATION);
    }
}
