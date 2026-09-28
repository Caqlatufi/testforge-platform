package io.testforge.dispatcher.reliability.retry;

public enum RetryEventType {
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELLED
}
