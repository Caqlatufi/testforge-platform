package io.testforge.dispatcher.port.outbound;

/**
 * 一次派发调用的可观测结论。
 */
public enum DispatchOutcome {
    PUBLISHED,
    PAUSED,
    REDIS_UNAVAILABLE
}
