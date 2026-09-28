package io.testforge.dispatcher.stream;

import java.time.Instant;

/**
 * 可用于健康检查或管理页面的只读状态。
 */
public record RedisDispatchAvailabilitySnapshot(
        RedisDispatchState state,
        Instant retryAt,
        String lastFailure
) {
}
