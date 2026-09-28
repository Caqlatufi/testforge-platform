package io.testforge.dispatcher.reliability.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    @Test
    void shouldApplyExponentialBackoffAndCapIt() {
        RetryPolicy policy = new RetryPolicy(6, Duration.ofSeconds(2), Duration.ofSeconds(10));

        assertThat(policy.backoffAfter(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.backoffAfter(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.backoffAfter(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.backoffAfter(4)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.backoffAfter(100)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.retryAt(3, Instant.parse("2026-09-18T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-09-18T00:00:08Z"));
    }

    @Test
    void shouldTreatMaxAttemptsAsTotalAttemptLimit() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(1));

        assertThat(policy.canRetry(1)).isTrue();
        assertThat(policy.canRetry(2)).isTrue();
        assertThat(policy.canRetry(3)).isFalse();
        assertThat(policy.canRetry(4)).isFalse();
    }

    @Test
    void shouldRejectUnboundedOrInvalidPolicies() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ZERO, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(3), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
