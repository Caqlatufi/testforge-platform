package io.testforge.runorchestrator.service.quota;

import io.testforge.casecatalog.workflow.compile.model.CompiledNode;
import io.testforge.casecatalog.workflow.compile.model.DependencyCondition;
import io.testforge.casecatalog.workflow.compile.model.ExecutableNodeType;
import io.testforge.casecatalog.workflow.compile.model.PublishNodeType;
import io.testforge.runorchestrator.port.dispatch.TaskDispatchRegistration;
import io.testforge.runorchestrator.port.dispatch.TaskDispatchRegistrationPort;
import io.testforge.runorchestrator.run.entity.TestRunEntity;
import io.testforge.runorchestrator.run.model.CreateRunCommand;
import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.service.scheduling.RunSchedulingService;
import io.testforge.runorchestrator.service.scheduling.SchedulingCandidateRepository;
import io.testforge.runorchestrator.service.scheduling.SchedulingCandidate;
import io.testforge.runorchestrator.task.dag.release.DagReleaseService;
import io.testforge.runorchestrator.task.dag.release.DagReleaseStateStore;
import io.testforge.runorchestrator.task.dag.release.JpaDagReleaseStateStore;
import io.testforge.runorchestrator.task.entity.TaskDependencyEntity;
import io.testforge.runorchestrator.task.entity.TestTaskEntity;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.repo.TaskDependencyRepository;
import io.testforge.runorchestrator.task.repo.TestTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = RunConcurrencyQuotaServiceIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:run_quota;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=BINARY",
        }
)
class RunConcurrencyQuotaServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-18T07:00:00Z");

    @Autowired
    private RunConcurrencyQuotaService service;

    @Autowired
    private RunQuotaRepository runRepository;

    @Autowired
    private TaskQuotaRepository taskRepository;

    @Autowired
    private RunSchedulingService schedulingService;

    @Autowired
    private TaskDependencyRepository dependencyRepository;

    @Autowired
    private RecordingDispatchPort dispatchPort;

    @AfterEach
    void cleanDatabase() {
        dispatchPort.clear();
        dependencyRepository.deleteAll();
        taskRepository.deleteAll();
        runRepository.deleteAll();
    }

    @Test
    void shouldNeverExceedMaxConcurrencyUnderConcurrentAcquisition() throws Exception {
        Fixture fixture = fixture(2, 8);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<QuotaReservation>> futures = new ArrayList<>();
        try {
            for (TestTaskEntity task : fixture.tasks()) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发配额测试未按时开始");
                    }
                    return service.acquire(fixture.run().getId(), task.getId(), NOW.plusSeconds(1));
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<QuotaReservation> results = new ArrayList<>();
            for (Future<QuotaReservation> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn(QuotaReservation::acquired).hasSize(2);
            assertThat(results).filteredOn(result -> result.outcome() == QuotaReservation.Outcome.LIMIT_REACHED)
                    .hasSize(6);
        } finally {
            executor.shutdownNow();
        }

        assertThat(taskRepository.countByRunIdAndStateIn(
                fixture.run().getId(),
                List.of(TaskState.DISPATCHED, TaskState.RUNNING)
        )).isEqualTo(2);
        assertThat(runRepository.findById(fixture.run().getId()).orElseThrow().getState())
                .isEqualTo(RunState.RUNNING);
    }

    @Test
    void shouldReleaseTerminalTaskAndReuseTheSlotWithoutRegressingRunToQueued() {
        Fixture fixture = fixture(1, 2);
        TestTaskEntity first = fixture.tasks().get(0);
        TestTaskEntity second = fixture.tasks().get(1);

        assertThat(service.acquire(fixture.run().getId(), first.getId(), NOW).acquired()).isTrue();
        moveToRunning(first.getId());
        QuotaRelease released = service.release(
                fixture.run().getId(),
                first.getId(),
                TaskState.SUCCEEDED,
                NOW.plusSeconds(2)
        );

        assertThat(released.outcome()).isEqualTo(QuotaRelease.Outcome.RELEASED);
        assertThat(released.occupiedSlots()).isZero();
        assertThat(released.runState()).isEqualTo(RunState.RUNNING);
        assertThat(service.acquire(fixture.run().getId(), second.getId(), NOW.plusSeconds(3)).acquired())
                .isTrue();

        moveToRunning(second.getId());
        QuotaRelease completed = service.release(
                fixture.run().getId(),
                second.getId(),
                TaskState.FAILED,
                NOW.plusSeconds(4)
        );
        assertThat(completed.runState()).isEqualTo(RunState.FAILED);
        assertThat(completed.occupiedSlots()).isZero();
    }

    @Test
    void shouldLetCancellationDominateALateTerminalResultAndReleaseTheSlotIdempotently() {
        Fixture fixture = fixture(1, 2);
        TestTaskEntity running = fixture.tasks().get(0);
        TestTaskEntity queued = fixture.tasks().get(1);
        service.acquire(fixture.run().getId(), running.getId(), NOW);
        moveToRunning(running.getId());

        TestRunEntity run = runRepository.findById(fixture.run().getId()).orElseThrow();
        run.requestCancellation(UUID.randomUUID(), "用户取消", NOW.plusSeconds(1));
        TestTaskEntity activeTask = taskRepository.findById(running.getId()).orElseThrow();
        activeTask.requestCancellation("用户取消", NOW.plusSeconds(1));
        TestTaskEntity queuedTask = taskRepository.findById(queued.getId()).orElseThrow();
        queuedTask.requestCancellation("用户取消", NOW.plusSeconds(1));
        taskRepository.saveAllAndFlush(List.of(activeTask, queuedTask));
        runRepository.saveAndFlush(run);

        QuotaRelease released = service.release(
                run.getId(),
                activeTask.getId(),
                TaskState.SUCCEEDED,
                NOW.plusSeconds(2)
        );
        QuotaRelease replayed = service.release(
                run.getId(),
                activeTask.getId(),
                TaskState.SUCCEEDED,
                NOW.plusSeconds(3)
        );

        assertThat(released.taskState()).isEqualTo(TaskState.CANCELLED);
        assertThat(released.runState()).isEqualTo(RunState.CANCELLED);
        assertThat(released.occupiedSlots()).isZero();
        assertThat(replayed.outcome()).isEqualTo(QuotaRelease.Outcome.ALREADY_RELEASED);
        assertThat(replayed.runState()).isEqualTo(RunState.CANCELLED);
    }

    @Test
    void shouldSelectPersistedCandidatesBeforeTheQuotaBoundaryMakesTheFinalDecision() {
        Fixture fixture = fixture(1, 3);

        List<SchedulingCandidate> selected = schedulingService.select(3, NOW.plusSeconds(1));
        List<QuotaReservation> reservations = selected.stream()
                .map(candidate -> service.acquire(candidate.runId(), candidate.taskId(), NOW.plusSeconds(1)))
                .toList();

        assertThat(selected).extracting(SchedulingCandidate::taskId)
                .containsExactlyElementsOf(fixture.tasks().stream().map(TestTaskEntity::getId).toList());
        assertThat(reservations).filteredOn(QuotaReservation::acquired).hasSize(1);
        assertThat(reservations).filteredOn(result -> result.outcome() == QuotaReservation.Outcome.LIMIT_REACHED)
                .hasSize(2);
        assertThat(runRepository.findById(fixture.run().getId()).orElseThrow().getState())
                .isEqualTo(RunState.RUNNING);
    }

    @Test
    void shouldRegisterDispatchWhenSuccessfulPredecessorReleasesSuccessor() {
        UUID runId = UUID.randomUUID();
        CreateRunCommand command = new CreateRunCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, 5, 1, UUID.randomUUID()
        );
        TestRunEntity run = runRepository.saveAndFlush(new TestRunEntity(
                runId, command, "sha256:" + "a".repeat(64), "{}",
                "sha256:" + "b".repeat(64), RunState.QUEUED, NOW
        ));
        TestTaskEntity predecessor = task(runId, 0, "airtest", TaskState.QUEUED);
        TestTaskEntity successor = task(runId, 1, "airtest", TaskState.WAITING_DEPENDENCY);
        taskRepository.saveAllAndFlush(List.of(predecessor, successor));
        dependencyRepository.saveAndFlush(new TaskDependencyEntity(
                UUID.randomUUID(), runId, predecessor.getId(), successor.getId(),
                DependencyCondition.ON_SUCCESS
        ));

        assertThat(service.acquire(runId, predecessor.getId(), NOW).acquired()).isTrue();
        moveToRunning(predecessor.getId());
        service.release(runId, predecessor.getId(), TaskState.SUCCEEDED, NOW.plusSeconds(2));

        assertThat(taskRepository.findById(successor.getId()).orElseThrow().getState())
                .isEqualTo(TaskState.QUEUED);
        assertThat(dispatchPort.registrations())
                .extracting(TaskDispatchRegistration::taskId)
                .containsExactly(successor.getId());
    }

    @Test
    void shouldKeepProcessAndDeviceConcurrencyIndependent() {
        UUID runId = UUID.randomUUID();
        CreateRunCommand command = new CreateRunCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, 5, 1, 1, 1, UUID.randomUUID(), null, Map.of()
        );
        TestRunEntity run = runRepository.saveAndFlush(new TestRunEntity(
                runId, command, "sha256:" + "a".repeat(64), "{}",
                "sha256:" + "b".repeat(64), RunState.QUEUED, NOW
        ));
        TestTaskEntity process = task(runId, 0, "pytest-http");
        TestTaskEntity device = task(runId, 1, "airtest");
        taskRepository.saveAllAndFlush(List.of(process, device));

        assertThat(service.acquire(run.getId(), process.getId(), NOW).acquired()).isTrue();
        assertThat(service.acquire(run.getId(), device.getId(), NOW).acquired()).isTrue();
        assertThat(taskRepository.countByRunIdAndStateIn(
                runId, List.of(TaskState.DISPATCHED, TaskState.RUNNING)
        )).isEqualTo(2);
    }

    private void moveToRunning(UUID taskId) {
        TestTaskEntity task = taskRepository.findById(taskId).orElseThrow();
        task.transitionTo(TaskState.RUNNING, NOW.plusSeconds(1));
        taskRepository.saveAndFlush(task);
    }

    private Fixture fixture(int maxConcurrency, int taskCount) {
        UUID runId = UUID.randomUUID();
        CreateRunCommand command = new CreateRunCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                5,
                maxConcurrency,
                UUID.randomUUID()
        );
        TestRunEntity run = runRepository.saveAndFlush(new TestRunEntity(
                runId,
                command,
                "sha256:" + "a".repeat(64),
                "{}",
                "sha256:" + "b".repeat(64),
                RunState.QUEUED,
                NOW
        ));
        List<TestTaskEntity> tasks = new ArrayList<>();
        for (int sequence = 0; sequence < taskCount; sequence++) {
            tasks.add(task(runId, sequence));
        }
        return new Fixture(run, taskRepository.saveAllAndFlush(tasks));
    }

    private TestTaskEntity task(UUID runId, int sequence) {
        return task(runId, sequence, "pytest-http");
    }

    private TestTaskEntity task(UUID runId, int sequence, String runner) {
        return task(runId, sequence, runner, TaskState.QUEUED);
    }

    private TestTaskEntity task(UUID runId, int sequence, String runner, TaskState state) {
        CompiledNode node = new CompiledNode(
                id(runId + "-node-" + sequence),
                "workflow/node-" + sequence,
                PublishNodeType.CASE,
                ExecutableNodeType.CASE,
                id(runId + "-case-" + sequence),
                id(runId + "-script-" + sequence),
                1,
                true,
                runner,
                "cases/" + sequence + ".py",
                "sha256:" + "c".repeat(64),
                30,
                Map.of()
        );
        return new TestTaskEntity(
                id(runId + "-task-" + sequence),
                runId,
                sequence,
                node,
                null,
                "[]",
                "{}",
                "{\"maxAttempts\":1}",
                state,
                NOW
        );
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Fixture(TestRunEntity run, List<TestTaskEntity> tasks) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {
            TestRunEntity.class,
            TestTaskEntity.class,
            TaskDependencyEntity.class
    })
    @EnableJpaRepositories(basePackageClasses = {
            RunQuotaRepository.class,
            TaskQuotaRepository.class,
            SchedulingCandidateRepository.class,
            TestTaskRepository.class,
            TaskDependencyRepository.class
    })
    static class TestApplication {

        @Bean
        DagReleaseStateStore dagReleaseStateStore(
                TestTaskRepository taskRepository,
                TaskDependencyRepository dependencyRepository
        ) {
            return new JpaDagReleaseStateStore(taskRepository, dependencyRepository);
        }

        @Bean
        DagReleaseService dagReleaseService(DagReleaseStateStore stateStore) {
            return new DagReleaseService(stateStore);
        }

        @Bean
        RunConcurrencyQuotaService runConcurrencyQuotaService(
                RunQuotaRepository runRepository,
                TaskQuotaRepository taskRepository,
                DagReleaseService dagReleaseService,
                RecordingDispatchPort dispatchPort,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper
        ) {
            return new RunConcurrencyQuotaService(
                    runRepository, taskRepository, dagReleaseService, dispatchPort, objectMapper
            );
        }

        @Bean
        RecordingDispatchPort recordingDispatchPort() {
            return new RecordingDispatchPort();
        }

        @Bean
        RunSchedulingService runSchedulingService(
                SchedulingCandidateRepository candidateRepository
        ) {
            return new RunSchedulingService(candidateRepository);
        }
    }

    static final class RecordingDispatchPort implements TaskDispatchRegistrationPort {
        private final List<TaskDispatchRegistration> registrations = new ArrayList<>();

        @Override
        public void register(TaskDispatchRegistration registration) {
            registrations.add(registration);
        }

        List<TaskDispatchRegistration> registrations() {
            return List.copyOf(registrations);
        }

        void clear() {
            registrations.clear();
        }
    }
}
