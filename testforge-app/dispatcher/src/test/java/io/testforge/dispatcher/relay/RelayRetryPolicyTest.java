package io.testforge.dispatcher.relay;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RelayRetryPolicyTest {

    private final RelayRetryPolicy policy = new RelayRetryPolicy(
            Duration.ofSeconds(2),
            Duration.ofSeconds(30)
    );

    @Test
    void doublesDelayAndCapsIt() {
        assertThat(policy.delayForAttempt(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayForAttempt(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayForAttempt(4)).isEqualTo(Duration.ofSeconds(16));
        assertThat(policy.delayForAttempt(5)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.delayForAttempt(100)).isEqualTo(Duration.ofSeconds(30));
    }
}
