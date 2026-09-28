package io.testforge.workergateway.registry.service;

import io.testforge.workergateway.registry.entity.WorkerNodeEntity;
import io.testforge.workergateway.registry.model.RegisterWorkerCommand;
import io.testforge.workergateway.registry.model.WorkerRequirement;
import io.testforge.workergateway.registry.model.WorkerStatus;
import io.testforge.workergateway.registry.repo.WorkerNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerRegistryServiceTest {

    private static final Instant START = Instant.parse("2026-09-18T08:00:00Z");

    private final Map<String, WorkerNodeEntity> workers = new LinkedHashMap<>();
    private final WorkerNodeRepository repository = mock(WorkerNodeRepository.class);
    private final MutableClock clock = new MutableClock(START);
    private WorkerRegistryService service;

    @BeforeEach
    void setUp() {
        workers.clear();
        when(repository.findByWorkerId(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(workers.get(invocation.getArgument(0)))
        );
        when(repository.findAllByOrderByWorkerIdAsc()).thenAnswer(invocation ->
                workers.values().stream()
                        .sorted((left, right) -> left.getWorkerId().compareTo(right.getWorkerId()))
                        .toList()
        );
        when(repository.saveAndFlush(any(WorkerNodeEntity.class))).thenAnswer(invocation -> {
            WorkerNodeEntity worker = invocation.getArgument(0);
            workers.put(worker.getWorkerId(), worker);
            return worker;
        });
        service = new WorkerRegistryService(repository, clock, Duration.ofSeconds(15));
    }

    @Test
    void repeatedRegistrationReusesInstanceIdentityAndSingleRecord() {
        var command = command("worker-linux-01", "1.0", Set.of("PYTEST_HTTP", "LINUX", "HTTP"), 4);

        var first = service.register(command);
        clock.advance(Duration.ofSeconds(3));
        var repeated = service.register(command);

        assertThat(repeated.instanceId()).isEqualTo(first.instanceId());
        assertThat(repeated.registeredAt()).isEqualTo(first.registeredAt());
        assertThat(repeated.lastHeartbeatAt()).isEqualTo(START.plusSeconds(3));
        assertThat(workers).hasSize(1);
    }

    @Test
    void registrationRefreshesCapabilitiesProtocolAndConcurrency() {
        var first = service.register(command(
                "worker-hybrid-01", "1.0", Set.of("PYTEST_HTTP", "LINUX", "HTTP"), 2
        ));

        clock.advance(Duration.ofSeconds(2));
        var updated = service.register(command(
                "worker-hybrid-01", "1.2", Set.of("AIRTEST", "WINDOWS", "WINDOWS_UI"), 1
        ));

        assertThat(updated.instanceId()).isEqualTo(first.instanceId());
        assertThat(updated.protocolVersion()).isEqualTo("1.2");
        assertThat(updated.capabilities()).containsExactlyInAnyOrder("AIRTEST", "WINDOWS", "WINDOWS_UI");
        assertThat(updated.maxConcurrency()).isEqualTo(1);
    }

    @Test
    void heartbeatRenewsOnlineDeadline() {
        service.register(command("worker-linux-01", "1.0", Set.of("PYTEST_HTTP", "LINUX"), 4));
        clock.advance(Duration.ofSeconds(14));

        var heartbeat = service.heartbeat("worker-linux-01");
        clock.advance(Duration.ofSeconds(14));

        assertThat(heartbeat.expiresAt()).isEqualTo(START.plusSeconds(29));
        assertThat(service.get("worker-linux-01").status()).isEqualTo(WorkerStatus.ONLINE);
    }

    @Test
    void workerBecomesOfflineAtExactExpiryBoundary() {
        service.register(command("worker-linux-01", "1.0", Set.of("PYTEST_HTTP", "LINUX"), 4));

        clock.advance(Duration.ofSeconds(15));

        assertThat(service.get("worker-linux-01").status()).isEqualTo(WorkerStatus.OFFLINE);
        assertThat(service.list(WorkerStatus.ONLINE)).isEmpty();
        assertThat(service.list(WorkerStatus.OFFLINE)).extracting("workerId")
                .containsExactly("worker-linux-01");
    }

    @Test
    void matchingRequiresOnlineProtocolRunnerPlatformAndEveryFeature() {
        service.register(command(
                "worker-linux-01", "1.2", Set.of("PYTEST_HTTP", "LINUX", "HTTP", "DOCKER"), 4
        ));
        service.register(command(
                "worker-windows-01", "1.0", Set.of("AIRTEST", "WINDOWS", "WINDOWS_UI"), 1
        ));
        service.register(command(
                "worker-old-offline", "1.2", Set.of("PYTEST_HTTP", "LINUX", "HTTP", "DOCKER"), 2
        ));
        clock.advance(Duration.ofSeconds(10));
        service.heartbeat("worker-linux-01");
        clock.advance(Duration.ofSeconds(6));

        var matches = service.findMatching(new WorkerRequirement(
                "1.1", "pytest-http", "LINUX", Set.of("HTTP", "DOCKER")
        ));

        assertThat(matches).extracting("workerId").containsExactly("worker-linux-01");
    }

    private RegisterWorkerCommand command(
            String workerId,
            String protocolVersion,
            Set<String> capabilities,
            int maxConcurrency
    ) {
        return new RegisterWorkerCommand(workerId, protocolVersion, capabilities, maxConcurrency);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
