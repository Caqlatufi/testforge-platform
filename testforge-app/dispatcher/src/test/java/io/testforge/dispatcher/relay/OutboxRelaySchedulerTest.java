package io.testforge.dispatcher.relay;

import io.testforge.dispatcher.outbox.service.OutboxClaimService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelaySchedulerTest {

    @Test
    void invokesRelayWithConfiguredClaimBoundary() {
        OutboxClaimService claimService = mock(OutboxClaimService.class);
        when(claimService.claim(eq("relay-1"), eq(25), eq(Duration.ofSeconds(30))))
                .thenReturn(List.of());
        OutboxRelay relay = new OutboxRelay(
                claimService,
                () -> message -> {
                },
                new RelayRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1)),
                Clock.systemUTC()
        );
        var scheduler = new OutboxRelayScheduler(relay, "relay-1", 25, Duration.ofSeconds(30));

        assertThat(scheduler.relayDueEvents()).isEqualTo(new RelayBatchResult(0, 0, 0, 0));
        verify(claimService).claim("relay-1", 25, Duration.ofSeconds(30));
    }

    @Test
    void validatesConfigurationBeforeScheduling() {
        OutboxRelay relay = mock(OutboxRelay.class);

        assertThatThrownBy(() -> new OutboxRelayScheduler(relay, " ", 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRelayScheduler(relay, "relay", 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRelayScheduler(relay, "relay", 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
