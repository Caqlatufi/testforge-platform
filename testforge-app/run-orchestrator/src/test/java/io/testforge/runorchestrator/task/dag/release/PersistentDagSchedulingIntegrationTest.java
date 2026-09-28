package io.testforge.runorchestrator.task.dag.release;

import io.testforge.casecatalog.workflow.compile.model.CompiledNode;
import io.testforge.casecatalog.workflow.compile.model.DependencyCondition;
import io.testforge.casecatalog.workflow.compile.model.ExecutableNodeType;
import io.testforge.casecatalog.workflow.compile.model.PublishNodeType;
import io.testforge.runorchestrator.run.entity.TestRunEntity;
import io.testforge.runorchestrator.run.model.CreateRunCommand;
import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.service.quota.RunConcurrencyQuotaService;
import io.testforge.runorchestrator.service.quota.RunQuotaRepository;
import io.testforge.runorchestrator.service.quota.TaskQuotaRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = PersistentDagSchedulingIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:persistent_dag;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=BINARY",
        }
)
class PersistentDagSchedulingIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-18T09:00:00Z");

    @Autowired
    private DagReleaseService dagReleaseService;

    @Autowired
    private RunConcurrencyQuotaService quotaService;

    @Autowired
    private RunQuotaRepository runRepository;

    @Autowired
    private TestTaskRepository taskRepository;

    @Autowired
    private TaskDependencyRepository dependencyRepository;

    @AfterEach
    void cleanDatabase() {
        dependencyRepository.deleteAll();
        taskRepository.deleteAll();
        runRepository.deleteAll();
    }

    @Test
    void shouldPersistRootReleaseOnlyOnce() {
        UUID runId = createRun(2);
        TestTaskEntity firstRoot = task(runId, 0, true, TaskState.CREATED);
        TestTaskEntity secondRoot = task(runId, 1, false, TaskState.CREATED);
        TestTaskEntity successor = task(runId, 2, true, TaskState.WAITING_DEPENDENCY);
        taskRepository.saveAllAndFlush(List.of(firstRoot, secondRoot, successor));
        dependencyRepository.saveAndFlush(dependency(
                runId,
                firstRoot,
                successor,
                DependencyCondition.ON_SUCCESS
        ));

        DagReleaseResult released = dagReleaseService.releaseRoots(runId);
        DagReleaseResult replayed = dagReleaseService.releaseRoots(runId);

        assertThat(released.releasedTaskIds()).containsExactlyInAnyOrder(
                firstRoot.getId(),
                secondRoot.getId()
        );
        assertThat(replayed.isEmpty()).isTrue();
        assertTask(firstRoot.getId(), TaskState.QUEUED, 1);
        assertTask(secondRoot.getId(), TaskState.QUEUED, 1);
        assertTask(successor.getId(), TaskState.WAITING_DEPENDENCY, 0);
    }

    @Test
    void shouldReleaseJoinOnceWhenParallelPredecessorsCompleteConcurrently() throws Exception {
        UUID runId = createRun(2);
        TestTaskEntity first = task(runId, 0, true, TaskState.QUEUED);
        TestTaskEntity second = task(runId, 1, true, TaskState.QUEUED);
        TestTaskEntity join = task(runId, 2, true, TaskState.WAITING_DEPENDENCY);
        taskRepository.saveAllAndFlush(List.of(first, second, join));
        dependencyRepository.saveAllAndFlush(List.of(
                dependency(runId, first, join, DependencyCondition.ON_SUCCESS),
                dependency(runId, second, join, DependencyCondition.ON_COMPLETION)
        ));
        start(runId, first.getId(), NOW);
        start(runId, second.getId(), NOW);

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstCompletion = CompletableFuture.supplyAsync(() -> {
                await(start);
                return quotaService.release(runId, first.getId(), TaskState.SUCCEEDED, NOW.plusSeconds(2));
            }, executor);
            var secondCompletion = CompletableFuture.supplyAsync(() -> {
                await(start);
                return quotaService.release(runId, second.getId(), TaskState.SUCCEEDED, NOW.plusSeconds(2));
            }, executor);
            start.countDown();
            CompletableFuture.allOf(firstCompletion, secondCompletion).get(10, TimeUnit.SECONDS);
        }

        assertTask(join.getId(), TaskState.QUEUED, 1);
        assertThat(dagReleaseService.releaseSuccessors(runId, first.getId()).isEmpty()).isTrue();
        assertTask(join.getId(), TaskState.QUEUED, 1);
        assertThat(runRepository.findById(runId).orElseThrow().getState()).isEqualTo(RunState.RUNNING);
    }

    @Test
    void shouldHonorFailureConditionsPropagateBlockedAndKeepIndependentBranchRunnable() {
        UUID runId = createRun(4);
        TestTaskEntity optionalFailure = task(runId, 0, false, TaskState.QUEUED);
        TestTaskEntity cleanup = task(runId, 1, true, TaskState.WAITING_DEPENDENCY);
        TestTaskEntity successOnly = task(runId, 2, true, TaskState.WAITING_DEPENDENCY);
        TestTaskEntity blockedChild = task(runId, 3, true, TaskState.WAITING_DEPENDENCY);
        TestTaskEntity cleanupAfterBlocked = task(runId, 4, true, TaskState.WAITING_DEPENDENCY);
        TestTaskEntity independent = task(runId, 5, true, TaskState.QUEUED);
        taskRepository.saveAllAndFlush(List.of(
                optionalFailure,
                cleanup,
                successOnly,
                blockedChild,
                cleanupAfterBlocked,
                independent
        ));
        dependencyRepository.saveAllAndFlush(List.of(
                dependency(runId, optionalFailure, cleanup, DependencyCondition.ON_COMPLETION),
                dependency(runId, optionalFailure, successOnly, DependencyCondition.ON_SUCCESS),
                dependency(runId, successOnly, blockedChild, DependencyCondition.ON_SUCCESS),
                dependency(runId, successOnly, cleanupAfterBlocked, DependencyCondition.ON_COMPLETION)
        ));
        start(runId, optionalFailure.getId(), NOW);

        quotaService.release(runId, optionalFailure.getId(), TaskState.FAILED, NOW.plusSeconds(2));

        assertThat(reload(cleanup).getState()).isEqualTo(TaskState.QUEUED);
        assertThat(reload(cleanupAfterBlocked).getState()).isEqualTo(TaskState.QUEUED);
        assertThat(reload(successOnly)).satisfies(task -> {
            assertThat(task.getState()).isEqualTo(TaskState.BLOCKED);
            assertThat(task.getBlockedByTaskId()).isEqualTo(optionalFailure.getId());
            assertThat(task.getBlockedReason()).contains("ON_SUCCESS").contains("FAILED");
        });
        assertThat(reload(blockedChild)).satisfies(task -> {
            assertThat(task.getState()).isEqualTo(TaskState.BLOCKED);
            assertThat(task.getBlockedByTaskId()).isEqualTo(successOnly.getId());
        });
        assertThat(reload(independent).getState()).isEqualTo(TaskState.QUEUED);
    }

    @Test
    void shouldAggregateWarningWhenOnlyOptionalTaskFails() {
        UUID runId = createRun(2);
        TestTaskEntity required = task(runId, 0, true, TaskState.QUEUED);
        TestTaskEntity optional = task(runId, 1, false, TaskState.QUEUED);
        taskRepository.saveAllAndFlush(List.of(required, optional));
        start(runId, required.getId(), NOW);
        start(runId, optional.getId(), NOW);

        quotaService.release(runId, required.getId(), TaskState.SUCCEEDED, NOW.plusSeconds(2));
        quotaService.release(runId, optional.getId(), TaskState.FAILED, NOW.plusSeconds(3));

        assertThat(runRepository.findById(runId).orElseThrow().getState())
                .isEqualTo(RunState.COMPLETED_WITH_WARNINGS);
    }

    private UUID createRun(int maxConcurrency) {
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
        runRepository.saveAndFlush(new TestRunEntity(
                runId,
                command,
                "sha256:" + "a".repeat(64),
                "{}",
                "sha256:" + "b".repeat(64),
                RunState.QUEUED,
                NOW
        ));
        return runId;
    }

    private TestTaskEntity task(UUID runId, int sequence, boolean required, TaskState state) {
        CompiledNode node = new CompiledNode(
                id(runId + "-node-" + sequence),
                "workflow/node-" + sequence,
                PublishNodeType.CASE,
                ExecutableNodeType.CASE,
                id(runId + "-case-" + sequence),
                id(runId + "-script-" + sequence),
                1,
                required,
                "pytest-http",
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

    private TaskDependencyEntity dependency(
            UUID runId,
            TestTaskEntity predecessor,
            TestTaskEntity successor,
            DependencyCondition condition
    ) {
        return new TaskDependencyEntity(
                UUID.randomUUID(),
                runId,
                predecessor.getId(),
                successor.getId(),
                condition
        );
    }

    private void start(UUID runId, UUID taskId, Instant at) {
        assertThat(quotaService.acquire(runId, taskId, at).acquired()).isTrue();
        TestTaskEntity task = taskRepository.findById(taskId).orElseThrow();
        task.transitionTo(TaskState.RUNNING, at.plusSeconds(1));
        taskRepository.saveAndFlush(task);
    }

    private TestTaskEntity reload(TestTaskEntity task) {
        return taskRepository.findById(task.getId()).orElseThrow();
    }

    private void assertTask(UUID taskId, TaskState state, long version) {
        assertThat(taskRepository.findById(taskId).orElseThrow()).satisfies(task -> {
            assertThat(task.getState()).isEqualTo(state);
            assertThat(task.getPersistenceVersion()).isEqualTo(version);
        });
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发完成未按时开始");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
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
        RunConcurrencyQuotaService quotaService(
                RunQuotaRepository runRepository,
                TaskQuotaRepository taskRepository,
                DagReleaseService dagReleaseService
        ) {
            return new RunConcurrencyQuotaService(runRepository, taskRepository, dagReleaseService);
        }
    }
}
