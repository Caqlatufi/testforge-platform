package io.testforge.dispatcher.outbox;

import io.testforge.dispatcher.DispatcherConfig;
import io.testforge.dispatcher.outbox.model.OutboxStatus;
import io.testforge.dispatcher.outbox.repo.OutboxEventRepository;
import io.testforge.dispatcher.outbox.service.OutboxClaimService;
import io.testforge.dispatcher.outbox.service.OutboxIdempotencyConflictException;
import io.testforge.dispatcher.outbox.service.OutboxRegistrationService;
import io.testforge.dispatcher.port.outbound.OutboxMessage;
import io.testforge.dispatcher.port.outbound.OutboxMessagePublisher;
import io.testforge.dispatcher.relay.OutboxRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = OutboxRelayIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:dispatcher_outbox;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=BINARY"
        }
)
class OutboxRelayIntegrationTest {

    private static final Instant START = Instant.parse("2026-09-18T08:00:00Z");

    @Autowired
    private OutboxRegistrationService registrationService;

    @Autowired
    private OutboxClaimService claimService;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MutableClock clock;

    @Autowired
    private RecordingPublisher publisher;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        clock.set(START);
        publisher.reset();
    }

    @Test
    void rollsBackRegistrationWithTheOwningBusinessTransaction() {
        UUID taskId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            register(taskId, Map.of("taskId", taskId, "runner", "pytest-http"));
            throw new IllegalStateException("force business rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(repository.count()).isZero();
        assertThat(relay.relayBatch("relay-a", 10, Duration.ofSeconds(30)).claimed()).isZero();
        assertThat(publisher.messages()).isEmpty();
    }

    @Test
    void registersIdempotentlyAndRejectsConflictingPayloads() {
        UUID taskId = UUID.randomUUID();
        var first = register(taskId, Map.of("taskId", taskId, "priority", 3));
        var duplicate = register(taskId, Map.of("priority", 3, "taskId", taskId));

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(repository.count()).isEqualTo(1);
        assertThatThrownBy(() -> register(taskId, Map.of("taskId", taskId, "priority", 8)))
                .isInstanceOf(OutboxIdempotencyConflictException.class)
                .hasMessageContaining("相同 eventKey");
    }

    @Test
    void retriesWithExponentialBackoffAndEventuallyMarksPublished() {
        UUID taskId = UUID.randomUUID();
        var event = register(taskId, Map.of("taskId", taskId));
        publisher.failNext(new IllegalStateException("redis unavailable"));

        var first = relay.relayBatch("relay-a", 10, Duration.ofSeconds(30));
        var afterFailure = repository.findById(event.id()).orElseThrow().toView();

        assertThat(first).extracting(
                result -> result.claimed(),
                result -> result.published(),
                result -> result.failed()
        ).containsExactly(1, 0, 1);
        assertThat(afterFailure.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterFailure.deliveryAttempts()).isEqualTo(1);
        assertThat(afterFailure.availableAt()).isEqualTo(START.plusSeconds(1));
        assertThat(afterFailure.lastError()).contains("redis unavailable");
        assertThat(relay.relayBatch("relay-a", 10, Duration.ofSeconds(30)).claimed()).isZero();

        clock.advance(Duration.ofSeconds(1));
        var retried = relay.relayBatch("relay-b", 10, Duration.ofSeconds(30));
        var published = repository.findById(event.id()).orElseThrow().toView();

        assertThat(retried.published()).isEqualTo(1);
        assertThat(published.status()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.deliveryAttempts()).isEqualTo(2);
        assertThat(published.publishedAt()).isEqualTo(START.plusSeconds(1));
        assertThat(publisher.messages()).hasSize(2);
        assertThat(publisher.messages()).extracting(OutboxMessage::eventId)
                .containsOnly(event.id());
    }

    @Test
    void reclaimsExpiredLeaseAfterRelayRestartWithoutAllowingStaleCompletion() {
        UUID taskId = UUID.randomUUID();
        var event = register(taskId, Map.of("taskId", taskId));
        var abandoned = claimService.claim("dead-relay", 1, Duration.ofSeconds(30)).getFirst();

        assertThat(claimService.claim("new-relay", 1, Duration.ofSeconds(30))).isEmpty();
        clock.advance(Duration.ofSeconds(30));
        var recovered = claimService.claim("new-relay", 1, Duration.ofSeconds(30)).getFirst();

        assertThat(recovered.id()).isEqualTo(event.id());
        assertThat(recovered.leaseToken()).isNotEqualTo(abandoned.leaseToken());
        assertThat(recovered.deliveryAttempt()).isEqualTo(2);
        assertThat(claimService.markPublished(abandoned.id(), abandoned.leaseToken(), clock.instant())).isFalse();
        assertThat(claimService.markPublished(recovered.id(), recovered.leaseToken(), clock.instant())).isTrue();
        assertThat(repository.findById(event.id()).orElseThrow().toView().status())
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void republishesAfterCrashBetweenBrokerAckAndPublishedMarkWithoutLosingEvent() {
        UUID taskId = UUID.randomUUID();
        var event = register(taskId, Map.of("taskId", taskId));
        var interruptedClaim = claimService.claim("crashed-relay", 1, Duration.ofSeconds(30)).getFirst();
        publisher.publish(message(interruptedClaim));

        clock.advance(Duration.ofSeconds(30));
        var recovered = relay.relayBatch("restarted-relay", 1, Duration.ofSeconds(30));

        assertThat(recovered.published()).isEqualTo(1);
        assertThat(publisher.messages()).hasSize(2);
        assertThat(publisher.messages()).extracting(OutboxMessage::eventId).containsOnly(event.id());
        assertThat(repository.findById(event.id()).orElseThrow().toView().status())
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void allowsOnlyOneConcurrentRelayToClaimAnEvent() throws Exception {
        UUID taskId = UUID.randomUUID();
        register(taskId, Map.of("taskId", taskId));
        CountDownLatch start = new CountDownLatch(1);

        var first = CompletableFuture.supplyAsync(() -> awaitAndClaim(start, "relay-a"));
        var second = CompletableFuture.supplyAsync(() -> awaitAndClaim(start, "relay-b"));
        start.countDown();

        var claims = new ArrayList<io.testforge.dispatcher.outbox.model.OutboxClaim>();
        claims.addAll(first.get(5, TimeUnit.SECONDS));
        claims.addAll(second.get(5, TimeUnit.SECONDS));
        assertThat(claims).hasSize(1);
        assertThat(repository.findAll().getFirst().toView().deliveryAttempts()).isEqualTo(1);
    }

    private io.testforge.dispatcher.outbox.model.OutboxEventView register(UUID taskId, Object payload) {
        return registrationService.register(
                "task-ready:" + taskId,
                taskId,
                "TASK",
                "TASK_READY",
                payload
        );
    }

    private List<io.testforge.dispatcher.outbox.model.OutboxClaim> awaitAndClaim(
            CountDownLatch start,
            String owner
    ) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发领取未按时开始");
            }
            return claimService.claim(owner, 1, Duration.ofSeconds(30));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private OutboxMessage message(io.testforge.dispatcher.outbox.model.OutboxClaim claim) {
        return new OutboxMessage(
                claim.id(),
                claim.eventKey(),
                claim.aggregateId(),
                claim.aggregateType(),
                claim.eventType(),
                claim.payload(),
                claim.deliveryAttempt()
        );
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(DispatcherConfig.class)
    static class TestApplication {

        @Bean
        MutableClock testClock() {
            return new MutableClock(START);
        }

        @Bean
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }
    }

    static final class RecordingPublisher implements OutboxMessagePublisher {

        private final List<OutboxMessage> messages = new ArrayList<>();
        private RuntimeException nextFailure;

        @Override
        public synchronized void publish(OutboxMessage message) {
            messages.add(message);
            if (nextFailure != null) {
                RuntimeException failure = nextFailure;
                nextFailure = null;
                throw failure;
            }
        }

        synchronized void failNext(RuntimeException failure) {
            nextFailure = failure;
        }

        synchronized List<OutboxMessage> messages() {
            return List.copyOf(messages);
        }

        synchronized void reset() {
            messages.clear();
            nextFailure = null;
        }
    }

    static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        synchronized void set(Instant instant) {
            this.instant = instant;
        }

        synchronized void advance(Duration duration) {
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
        public synchronized Instant instant() {
            return instant;
        }
    }
}
