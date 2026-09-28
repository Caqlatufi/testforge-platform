package io.testforge.dispatcher.reliability.retry;

public enum RetryConvergenceOutcome {
    APPLIED,
    ALREADY_CONVERGED,
    STALE_ATTEMPT
}
