package io.testforge.runorchestrator.repo.attempt;

import io.testforge.runorchestrator.entity.attempt.TaskAttemptEntity;
import io.testforge.runorchestrator.model.attempt.AttemptState;
import io.testforge.runorchestrator.model.attempt.AttemptTransitionOutcome;
import io.testforge.runorchestrator.service.attempt.AttemptStateConflictException;
import io.testforge.runorchestrator.service.attempt.AttemptStateTransitionException;
import io.testforge.runorchestrator.service.attempt.AttemptTransitionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = TaskAttemptRepositoryIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:attempt_state;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=BINARY",
        }
)
class TaskAttemptRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-18T04:00:00Z");

    @Autowired
    private TaskAttemptRepository repository;

    @Autowired
    private AttemptTransitionService service;

    @AfterEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void shouldPersistAttemptAndFindItByTaskAndAttemptNumber() {
        TaskAttemptEntity attempt = repository.saveAndFlush(newAttempt(UUID.randomUUID(), 1));

        TaskAttemptEntity reloaded = repository.findByTaskIdAndAttemptNo(
                attempt.getTaskId(),
                attempt.getAttemptNo()
        ).orElseThrow();

        assertThat(reloaded.getState()).isEqualTo(AttemptState.CREATED);
        assertThat(reloaded.getVersion()).isZero();
        assertThat(reloaded.getWorkerId()).isEqualTo("worker-3");
        assertThat(reloaded.getLeaseUntil()).isEqualTo(NOW.plus(30, ChronoUnit.SECONDS));
    }

    @Test
    void shouldApplyLegalTransitionsAndKeepDuplicateTerminalTransitionIdempotent() {
        TaskAttemptEntity attempt = repository.saveAndFlush(newAttempt(UUID.randomUUID(), 1));

        var running = service.transition(attempt.getId(), 0, AttemptState.RUNNING, NOW.plusSeconds(1));
        var succeeded = service.transition(attempt.getId(), 1, AttemptState.SUCCEEDED, NOW.plusSeconds(2));
        var replayed = service.transition(attempt.getId(), 1, AttemptState.SUCCEEDED, NOW.plusSeconds(3));

        assertThat(running.outcome()).isEqualTo(AttemptTransitionOutcome.APPLIED);
        assertThat(running.version()).isEqualTo(1);
        assertThat(succeeded.outcome()).isEqualTo(AttemptTransitionOutcome.APPLIED);
        assertThat(succeeded.version()).isEqualTo(2);
        assertThat(replayed.outcome()).isEqualTo(AttemptTransitionOutcome.ALREADY_APPLIED);
        assertThat(replayed.version()).isEqualTo(2);

        TaskAttemptEntity reloaded = repository.findById(attempt.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(AttemptState.SUCCEEDED);
        assertThat(reloaded.getVersion()).isEqualTo(2);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void shouldRejectIllegalTransitionAndNeverOverwriteATerminalState() {
        TaskAttemptEntity attempt = repository.saveAndFlush(newAttempt(UUID.randomUUID(), 1));

        assertThatThrownBy(() -> service.transition(
                attempt.getId(),
                0,
                AttemptState.SUCCEEDED,
                NOW.plusSeconds(1)
        )).isInstanceOf(AttemptStateTransitionException.class);

        service.transition(attempt.getId(), 0, AttemptState.RUNNING, NOW.plusSeconds(2));
        service.transition(attempt.getId(), 1, AttemptState.FAILED, NOW.plusSeconds(3));

        assertThatThrownBy(() -> service.transition(
                attempt.getId(),
                2,
                AttemptState.SUCCEEDED,
                NOW.plusSeconds(4)
        )).isInstanceOf(AttemptStateTransitionException.class)
                .hasMessageContaining("FAILED -> SUCCEEDED");

        TaskAttemptEntity reloaded = repository.findById(attempt.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(AttemptState.FAILED);
        assertThat(reloaded.getVersion()).isEqualTo(2);
    }

    @Test
    void shouldReportStaleVersionAsAStateConflict() {
        TaskAttemptEntity attempt = repository.saveAndFlush(newAttempt(UUID.randomUUID(), 1));
        service.transition(attempt.getId(), 0, AttemptState.RUNNING, NOW.plusSeconds(1));

        assertThatThrownBy(() -> service.transition(
                attempt.getId(),
                0,
                AttemptState.CANCELLED,
                NOW.plusSeconds(2)
        )).isInstanceOf(AttemptStateConflictException.class)
                .hasMessageContaining("expected=RUNNING@0")
                .hasMessageContaining("actual=RUNNING@1");
    }

    @Test
    void shouldAllowOnlyOneConcurrentTerminalConditionalUpdate() throws Exception {
        TaskAttemptEntity attempt = repository.saveAndFlush(newAttempt(UUID.randomUUID(), 1));
        service.transition(attempt.getId(), 0, AttemptState.RUNNING, NOW.plusSeconds(1));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> succeeded = executor.submit(() -> raceTerminalUpdate(
                    ready,
                    start,
                    attempt.getId(),
                    AttemptState.SUCCEEDED
            ));
            Future<Integer> failed = executor.submit(() -> raceTerminalUpdate(
                    ready,
                    start,
                    attempt.getId(),
                    AttemptState.FAILED
            ));

            ready.await();
            start.countDown();

            assertThat(List.of(succeeded.get(), failed.get()))
                    .containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
        }

        TaskAttemptEntity winner = repository.findById(attempt.getId()).orElseThrow();
        assertThat(winner.getState()).isIn(AttemptState.SUCCEEDED, AttemptState.FAILED);
        assertThat(winner.getVersion()).isEqualTo(2);
    }

    private int raceTerminalUpdate(
            CountDownLatch ready,
            CountDownLatch start,
            UUID attemptId,
            AttemptState target
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return repository.compareAndSetState(
                attemptId,
                AttemptState.RUNNING,
                1,
                target,
                NOW.plusSeconds(2)
        );
    }

    private TaskAttemptEntity newAttempt(UUID taskId, int attemptNo) {
        return new TaskAttemptEntity(
                UUID.randomUUID(),
                taskId,
                attemptNo,
                "worker-3",
                UUID.randomUUID(),
                NOW.plus(30, ChronoUnit.SECONDS),
                NOW
        );
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = TaskAttemptEntity.class)
    @EnableJpaRepositories(basePackageClasses = TaskAttemptRepository.class)
    static class TestApplication {

        @Bean
        AttemptTransitionService attemptTransitionService(TaskAttemptRepository repository) {
            return new AttemptTransitionService(repository);
        }
    }
}
