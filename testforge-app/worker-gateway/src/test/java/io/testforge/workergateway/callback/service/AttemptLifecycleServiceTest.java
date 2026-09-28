package io.testforge.workergateway.callback.service;

import io.testforge.workergateway.callback.model.AttemptHeartbeatRequest;
import io.testforge.workergateway.callback.model.AttemptStartRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttemptLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T06:00:00Z");

    @Mock
    private AttemptExecutionGateway gateway;

    @Test
    void startValidatesLeaseAndExtendsItUsingServerClock() {
        UUID attemptId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        when(gateway.renew(attemptId, "worker-1", leaseToken, NOW, NOW.plusSeconds(30)))
                .thenReturn(Optional.of(new AttemptLeaseSnapshot(
                        attemptId, taskId, NOW.plusSeconds(30)
                )));
        AttemptLifecycleService service = service();

        var response = service.start(
                attemptId, new AttemptStartRequest("worker-1", leaseToken)
        );

        assertThat(response.state()).isEqualTo("RUNNING");
        assertThat(response.leaseUntil()).isEqualTo(NOW.plusSeconds(30));
        assertThat(response.progress()).isNull();
    }

    @Test
    void heartbeatEchoesProgressButUsesServerTimeForLeaseValidation() {
        UUID attemptId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        BigDecimal progress = new BigDecimal("0.42");
        when(gateway.renew(attemptId, "worker-1", leaseToken, NOW, NOW.plusSeconds(30)))
                .thenReturn(Optional.of(new AttemptLeaseSnapshot(
                        attemptId, UUID.randomUUID(), NOW.plusSeconds(30)
                )));
        AttemptLifecycleService service = service();

        var response = service.heartbeat(attemptId, new AttemptHeartbeatRequest(
                "worker-1", leaseToken, progress, NOW.minusSeconds(5)
        ));

        assertThat(response.progress()).isEqualByComparingTo(progress);
        assertThat(response.acceptedAt()).isEqualTo(NOW);
        verify(gateway).renew(attemptId, "worker-1", leaseToken, NOW, NOW.plusSeconds(30));
    }

    @Test
    void invalidOrExpiredLeaseIsRejected() {
        UUID attemptId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        when(gateway.renew(attemptId, "worker-1", leaseToken, NOW, NOW.plusSeconds(30)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().start(
                attemptId, new AttemptStartRequest("worker-1", leaseToken)
        )).isInstanceOf(AttemptLeaseExpiredException.class);
    }

    private AttemptLifecycleService service() {
        return new AttemptLifecycleService(
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30)
        );
    }
}
