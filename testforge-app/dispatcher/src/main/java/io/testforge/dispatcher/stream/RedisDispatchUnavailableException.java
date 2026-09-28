package io.testforge.dispatcher.stream;

import java.time.Instant;

/**
 * Redis 暂时不可用或处于暂停窗口。Relay 捕获该异常后保留 MySQL Outbox 并按策略重试。
 */
public final class RedisDispatchUnavailableException extends RuntimeException {

    private final Instant retryAt;

    public RedisDispatchUnavailableException(String message, Instant retryAt) {
        super(message);
        this.retryAt = retryAt;
    }

    public RedisDispatchUnavailableException(String message, Instant retryAt, Throwable cause) {
        super(message, cause);
        this.retryAt = retryAt;
    }

    public Instant retryAt() {
        return retryAt;
    }
}
