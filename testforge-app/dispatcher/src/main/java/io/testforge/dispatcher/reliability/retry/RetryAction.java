package io.testforge.dispatcher.reliability.retry;

public enum RetryAction {
    COMPLETE,
    REQUEUE,
    TERMINATE,
    CANCEL,
    IGNORE
}
