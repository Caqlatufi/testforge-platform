package io.testforge.workergateway.device.lease;

import io.testforge.workergateway.DeviceLeaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:device_lease;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "testforge.worker-gateway.device-lease.lease-duration=15s",
        "testforge.worker-gateway.device-lease.reaper.enabled=false"
})
class DeviceLeasePersistenceIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-18T03:00:00Z");

    @Autowired
    private DeviceLeaseService service;

    @Autowired
    private DeviceSlotLeaseRepository repository;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetDatabaseAndClock() {
        repository.deleteAll();
        clock.set(BASE_TIME);
    }

    @Test
    void allowsOnlyOneAttemptToReserveTheSameDeviceConcurrently() throws Exception {
        UUID deviceSlotId = UUID.randomUUID();
        service.initializeAvailable(deviceSlotId);
        List<UUID> attempts = java.util.stream.IntStream.range(0, 12)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        CountDownLatch ready = new CountDownLatch(attempts.size());
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(attempts.size())) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (UUID attemptId : attempts) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        service.reserve(deviceSlotId, attemptId);
                        return true;
                    } catch (DeviceLeaseUnavailableException exception) {
                        return false;
                    }
                }));
            }
            ready.await();
            start.countDown();

            long winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        }

        DeviceLeaseSnapshot stored = service.load(deviceSlotId);
        assertThat(stored.state()).isEqualTo(DeviceLeaseState.RESERVED);
        assertThat(stored.currentAttemptId()).isIn(attempts);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void duplicateReservationReplaysLeaseAndHeartbeatRejectsStaleCredentials() {
        UUID deviceSlotId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        service.initializeAvailable(deviceSlotId);

        DeviceLeaseSnapshot first = service.reserve(deviceSlotId, attemptId);
        DeviceLeaseSnapshot duplicate = service.reserve(deviceSlotId, attemptId);
        assertThat(duplicate.leaseToken()).isEqualTo(first.leaseToken());
        assertThat(duplicate.leaseUntil()).isEqualTo(first.leaseUntil());

        clock.advance(Duration.ofSeconds(5));
        DeviceLeaseSnapshot heartbeat = service.heartbeat(
                deviceSlotId,
                attemptId,
                first.leaseToken()
        );
        assertThat(heartbeat.leaseUntil()).isEqualTo(BASE_TIME.plusSeconds(20));
        assertThat(heartbeat.version()).isGreaterThan(first.version());

        assertThatThrownBy(() -> service.heartbeat(
                deviceSlotId,
                attemptId,
                UUID.randomUUID()
        )).isInstanceOf(DeviceLeaseRejectedException.class);
    }

    @Test
    void cancellationAndTerminalPathsReleaseWithoutLettingOldAttemptClearNewLease() {
        UUID deviceSlotId = UUID.randomUUID();
        UUID cancelledAttempt = UUID.randomUUID();
        UUID completedAttempt = UUID.randomUUID();
        UUID nextAttempt = UUID.randomUUID();
        service.initializeAvailable(deviceSlotId);

        service.reserve(deviceSlotId, cancelledAttempt);
        assertThat(service.releaseCancelled(cancelledAttempt)).isTrue();
        assertThat(service.releaseCancelled(cancelledAttempt)).isFalse();
        assertThat(service.load(deviceSlotId).releaseReason())
                .isEqualTo(DeviceLeaseReleaseReason.CANCELLED);

        service.reserve(deviceSlotId, completedAttempt);
        assertThat(service.releaseTerminal(completedAttempt)).isTrue();
        DeviceLeaseSnapshot reservedAgain = service.reserve(deviceSlotId, nextAttempt);
        assertThat(reservedAgain.currentAttemptId()).isEqualTo(nextAttempt);

        assertThat(service.releaseTerminal(completedAttempt)).isFalse();
        assertThat(service.load(deviceSlotId).currentAttemptId()).isEqualTo(nextAttempt);
    }

    @Test
    void expiredLeaseIsEventuallyReleasedAndCanBeReservedAgain() {
        UUID deviceSlotId = UUID.randomUUID();
        UUID expiredAttempt = UUID.randomUUID();
        service.initializeAvailable(deviceSlotId);
        DeviceLeaseSnapshot expired = service.reserve(deviceSlotId, expiredAttempt);

        clock.set(expired.leaseUntil().plusMillis(1));
        assertThatThrownBy(() -> service.heartbeat(
                deviceSlotId,
                expiredAttempt,
                expired.leaseToken()
        )).isInstanceOf(DeviceLeaseRejectedException.class);

        DeviceLeaseReapResult reaped = service.releaseExpired();
        assertThat(reaped.candidateCount()).isEqualTo(1);
        assertThat(reaped.releasedDeviceSlotIds()).containsExactly(deviceSlotId);

        DeviceLeaseSnapshot available = service.load(deviceSlotId);
        assertThat(available.state()).isEqualTo(DeviceLeaseState.AVAILABLE);
        assertThat(available.releaseReason()).isEqualTo(DeviceLeaseReleaseReason.EXPIRED);
        assertThat(available.currentAttemptId()).isNull();

        DeviceLeaseSnapshot replacement = service.reserve(deviceSlotId, UUID.randomUUID());
        assertThat(replacement.state()).isEqualTo(DeviceLeaseState.RESERVED);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({DeviceLeaseConfig.class, ClockTestConfig.class})
    static class TestApplication {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockTestConfig {

        @Bean
        MutableClock mutableClock() {
            return new MutableClock(BASE_TIME);
        }
    }

    static final class MutableClock extends Clock {

        private volatile Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void set(Instant instant) {
            this.current = instant;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
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
            return current;
        }
    }
}
