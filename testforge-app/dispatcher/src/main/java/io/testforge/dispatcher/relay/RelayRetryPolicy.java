package io.testforge.dispatcher.relay;

import java.time.Duration;
import java.util.Objects;

public class RelayRetryPolicy {

    private final Duration initialDelay;
    private final Duration maximumDelay;

    public RelayRetryPolicy(Duration initialDelay, Duration maximumDelay) {
        this.initialDelay = requirePositive(initialDelay, "initialDelay");
        this.maximumDelay = requirePositive(maximumDelay, "maximumDelay");
        if (maximumDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maximumDelay 不能小于 initialDelay");
        }
    }

    public Duration delayForAttempt(int deliveryAttempt) {
        if (deliveryAttempt < 1) {
            throw new IllegalArgumentException("deliveryAttempt 必须大于 0");
        }
        long multiplier = 1L << Math.min(deliveryAttempt - 1, 30);
        try {
            Duration candidate = initialDelay.multipliedBy(multiplier);
            return candidate.compareTo(maximumDelay) > 0 ? maximumDelay : candidate;
        } catch (ArithmeticException exception) {
            return maximumDelay;
        }
    }

    private static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " 必须大于 0");
        }
        return value;
    }
}
