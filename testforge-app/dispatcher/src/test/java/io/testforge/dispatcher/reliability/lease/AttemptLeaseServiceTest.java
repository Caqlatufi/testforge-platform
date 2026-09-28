package io.testforge.dispatcher.reliability.lease;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttemptLeaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T06:00:00Z");
    private static final UUID ATTEMPT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID TOKEN = UUID.fromString("30000000-0000-4000-8000-000000000001");

    @Test
    void shouldIssueOpaqueTokenAndRenewLeaseOnFiveSecondHeartbeatCadence() {
        InMemoryAttemptLeaseStore store = new InMemoryAttemptLeaseStore();
        LeasePolicy policy = LeasePolicy.defaults();
        AttemptLeaseService issuer = service(store, policy, NOW, TOKEN);

        LeaseGrant grant = issuer.issue(ATTEMPT_ID, TASK_ID, "worker-1");

        assertThat(grant.leaseToken()).isEqualTo(TOKEN);
        assertThat(grant.heartbeatInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(grant.leaseUntil()).isEqualTo(NOW.plusSeconds(15));

        Instant heartbeatAt = NOW.plusSeconds(5);
        LeaseHeartbeat heartbeat = service(store, policy, heartbeatAt, UUID.randomUUID())
                .heartbeat(ATTEMPT_ID, "worker-1", TOKEN);

        assertThat(heartbeat.acceptedAt()).isEqualTo(heartbeatAt);
        assertThat(heartbeat.nextHeartbeatAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(heartbeat.leaseUntil()).isEqualTo(NOW.plusSeconds(20));
        assertThat(heartbeat.version()).isEqualTo(1);
    }

    @Test
    void shouldRejectOldTokenAfterAReplacementAttemptWasIssued() {
        InMemoryAttemptLeaseStore store = new InMemoryAttemptLeaseStore();
        UUID replacementAttemptId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID replacementToken = UUID.fromString("30000000-0000-4000-8000-000000000002");
        service(store, LeasePolicy.defaults(), NOW, TOKEN)
                .issue(ATTEMPT_ID, TASK_ID, "worker-1");
        service(store, LeasePolicy.defaults(), NOW.plusSeconds(1), replacementToken)
                .issue(replacementAttemptId, TASK_ID, "worker-2");

        assertThatThrownBy(() -> service(
                store,
                LeasePolicy.defaults(),
                NOW.plusSeconds(5),
                UUID.randomUUID()
        ).heartbeat(replacementAttemptId, "worker-2", TOKEN))
                .isInstanceOf(LeaseRejectedException.class)
                .hasMessageContaining(replacementAttemptId.toString());

        assertThat(store.snapshot(replacementAttemptId).orElseThrow().leaseToken())
                .isEqualTo(replacementToken);
        assertThat(store.snapshot(replacementAttemptId).orElseThrow().version()).isZero();
    }

    @Test
    void shouldRejectDuplicateIssuanceForTheSameAttempt() {
        InMemoryAttemptLeaseStore store = new InMemoryAttemptLeaseStore();
        AttemptLeaseService service = service(store, LeasePolicy.defaults(), NOW, TOKEN);
        service.issue(ATTEMPT_ID, TASK_ID, "worker-1");

        assertThatThrownBy(() -> service.issue(ATTEMPT_ID, TASK_ID, "worker-1"))
                .isInstanceOf(LeaseIssueConflictException.class);
    }

    @RepeatedTest(20)
    void shouldAllowOnlyHeartbeatOrReaperToWinAtTheExpiryBoundary() throws Exception {
        InMemoryAttemptLeaseStore store = new InMemoryAttemptLeaseStore();
        LeasePolicy policy = LeasePolicy.defaults();
        service(store, policy, NOW, TOKEN).issue(ATTEMPT_ID, TASK_ID, "worker-1");
        Instant boundary = NOW.plus(policy.leaseDuration());
        AttemptLeaseService heartbeatService = service(store, policy, boundary, UUID.randomUUID());
        LeaseReaper reaper = reaper(store, policy, boundary);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> heartbeat = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    heartbeatService.heartbeat(ATTEMPT_ID, "worker-1", TOKEN);
                    return 1;
                } catch (LeaseRejectedException ignored) {
                    return 0;
                }
            });
            Future<Integer> reap = executor.submit(() -> {
                ready.countDown();
                start.await();
                return reaper.reapExpired().lostCount();
            });

            ready.await();
            start.countDown();

            assertThat(heartbeat.get() + reap.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        StoredLease stored = store.entry(ATTEMPT_ID).orElseThrow();
        assertThat(stored.snapshot().version()).isEqualTo(1);
        assertThat(stored.status()).isIn(StoreStatus.ACTIVE, StoreStatus.LOST);
    }

    @Test
    void shouldRecoverExpiredLeaseAfterReaperRestartExactlyOnce() {
        InMemoryAttemptLeaseStore persistedStore = new InMemoryAttemptLeaseStore();
        LeasePolicy policy = LeasePolicy.defaults();
        service(persistedStore, policy, NOW, TOKEN).issue(ATTEMPT_ID, TASK_ID, "worker-1");
        Instant restartedAt = NOW.plus(policy.leaseDuration()).plusMillis(1);

        LeaseReapResult afterRestart = reaper(persistedStore, policy, restartedAt).reapExpired();
        LeaseReapResult duplicateScan = reaper(persistedStore, policy, restartedAt.plusSeconds(5))
                .reapExpired();

        assertThat(afterRestart.candidateCount()).isEqualTo(1);
        assertThat(afterRestart.lostLeases())
                .extracting(LostLease::attemptId)
                .containsExactly(ATTEMPT_ID);
        assertThat(duplicateScan.candidateCount()).isZero();
        assertThat(duplicateScan.lostCount()).isZero();
        assertThat(persistedStore.entry(ATTEMPT_ID).orElseThrow().status())
                .isEqualTo(StoreStatus.LOST);
        assertThatThrownBy(() -> service(
                persistedStore,
                policy,
                restartedAt.plusSeconds(6),
                UUID.randomUUID()
        ).heartbeat(ATTEMPT_ID, "worker-1", TOKEN))
                .isInstanceOf(LeaseRejectedException.class);
    }

    @Test
    void shouldKeepRetryAndBackoffOutsideLeasePolicy() {
        assertThat(LeasePolicy.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("heartbeatInterval", "leaseDuration", "reaperBatchSize");
        assertThatThrownBy(() -> new LeasePolicy(
                Duration.ofSeconds(4),
                Duration.ofSeconds(15),
                100
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 秒");
    }

    private static AttemptLeaseService service(
            AttemptLeaseStore store,
            LeasePolicy policy,
            Instant now,
            UUID token
    ) {
        return new AttemptLeaseService(
                store,
                policy,
                Clock.fixed(now, ZoneOffset.UTC),
                () -> token
        );
    }

    private static LeaseReaper reaper(
            AttemptLeaseStore store,
            LeasePolicy policy,
            Instant now
    ) {
        return new LeaseReaper(store, policy, Clock.fixed(now, ZoneOffset.UTC));
    }

    private enum StoreStatus {
        ACTIVE,
        LOST
    }

    private record StoredLease(LeaseSnapshot snapshot, StoreStatus status) {
    }

    /** 模拟由 MySQL 条件更新提供的原子性，测试服务层的竞争协议。 */
    private static final class InMemoryAttemptLeaseStore implements AttemptLeaseStore {

        private final Map<UUID, StoredLease> leases = new ConcurrentHashMap<>();

        @Override
        public boolean issue(LeaseSnapshot initialLease, Instant issuedAt) {
            return leases.putIfAbsent(
                    initialLease.attemptId(),
                    new StoredLease(initialLease, StoreStatus.ACTIVE)
            ) == null;
        }

        @Override
        public Optional<LeaseSnapshot> heartbeat(
                UUID attemptId,
                String workerId,
                UUID leaseToken,
                Instant acceptedAt,
                Instant extendedUntil
        ) {
            AtomicReference<LeaseSnapshot> renewed = new AtomicReference<>();
            leases.computeIfPresent(attemptId, (ignored, current) -> {
                LeaseSnapshot snapshot = current.snapshot();
                if (current.status() != StoreStatus.ACTIVE
                        || !snapshot.workerId().equals(workerId)
                        || !snapshot.leaseToken().equals(leaseToken)
                        || snapshot.leaseUntil().isBefore(acceptedAt)) {
                    return current;
                }
                LeaseSnapshot updated = new LeaseSnapshot(
                        snapshot.attemptId(),
                        snapshot.taskId(),
                        snapshot.workerId(),
                        snapshot.leaseToken(),
                        extendedUntil,
                        snapshot.version() + 1
                );
                renewed.set(updated);
                return new StoredLease(updated, StoreStatus.ACTIVE);
            });
            return Optional.ofNullable(renewed.get());
        }

        @Override
        public List<LeaseSnapshot> findExpired(Instant expiredAtOrBefore, int limit) {
            List<LeaseSnapshot> result = new ArrayList<>();
            leases.values().stream()
                    .filter(entry -> entry.status() == StoreStatus.ACTIVE)
                    .map(StoredLease::snapshot)
                    .filter(snapshot -> !snapshot.leaseUntil().isAfter(expiredAtOrBefore))
                    .sorted(Comparator.comparing(LeaseSnapshot::leaseUntil)
                            .thenComparing(LeaseSnapshot::attemptId))
                    .limit(limit)
                    .forEach(result::add);
            return List.copyOf(result);
        }

        @Override
        public boolean markLostIfExpired(LeaseSnapshot candidate, Instant detectedAt) {
            AtomicReference<Boolean> changed = new AtomicReference<>(false);
            leases.computeIfPresent(candidate.attemptId(), (ignored, current) -> {
                LeaseSnapshot actual = current.snapshot();
                if (current.status() != StoreStatus.ACTIVE
                        || !actual.leaseToken().equals(candidate.leaseToken())
                        || actual.version() != candidate.version()
                        || actual.leaseUntil().isAfter(detectedAt)) {
                    return current;
                }
                changed.set(true);
                LeaseSnapshot lost = new LeaseSnapshot(
                        actual.attemptId(),
                        actual.taskId(),
                        actual.workerId(),
                        actual.leaseToken(),
                        actual.leaseUntil(),
                        actual.version() + 1
                );
                return new StoredLease(lost, StoreStatus.LOST);
            });
            return changed.get();
        }

        Optional<LeaseSnapshot> snapshot(UUID attemptId) {
            return entry(attemptId).map(StoredLease::snapshot);
        }

        Optional<StoredLease> entry(UUID attemptId) {
            return Optional.ofNullable(leases.get(attemptId));
        }
    }
}
