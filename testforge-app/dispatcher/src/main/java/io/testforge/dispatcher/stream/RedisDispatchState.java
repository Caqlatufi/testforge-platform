package io.testforge.dispatcher.stream;

/**
 * Redis 派发连接的本地保护状态。
 */
public enum RedisDispatchState {
    ACTIVE,
    PAUSED,
    PROBING
}
