package io.testforge.runorchestrator.run.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.workflow.compile.entity.PublishedWorkflowVersionEntity;
import io.testforge.casecatalog.workflow.compile.model.CaseReferenceInput;
import io.testforge.casecatalog.workflow.compile.model.DependencyCondition;
import io.testforge.casecatalog.workflow.compile.model.ExecutableNodeType;
import io.testforge.casecatalog.workflow.compile.model.PublishNodeType;
import io.testforge.casecatalog.workflow.compile.model.ReferenceCatalogInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowEdgeInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowGraphInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowNodeInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowPublishInput;
import io.testforge.casecatalog.workflow.compile.repo.PublishedWorkflowVersionRepository;
import io.testforge.casecatalog.workflow.compile.service.WorkflowPublishService;
import io.testforge.projectcatalog.ProjectCatalogConfig;
import io.testforge.projectcatalog.model.CreateEnvironmentCommand;
import io.testforge.projectcatalog.model.CreateProjectCommand;
import io.testforge.projectcatalog.model.CreateTargetCommand;
import io.testforge.projectcatalog.model.TargetType;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import io.testforge.projectcatalog.revision.RevisionSelector;
import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.runorchestrator.entity.attempt.TaskAttemptEntity;
import io.testforge.runorchestrator.repo.attempt.TaskAttemptRepository;
import io.testforge.runorchestrator.run.entity.TestRunEntity;
import io.testforge.runorchestrator.run.model.CancelRunCommand;
import io.testforge.runorchestrator.run.model.CreateRunCommand;
import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.run.repo.TestRunRepository;
import io.testforge.runorchestrator.task.entity.TaskDependencyEntity;
import io.testforge.runorchestrator.task.entity.TestTaskEntity;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.repo.TaskDependencyRepository;
import io.testforge.runorchestrator.task.repo.TestTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = RunTaskServiceIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:run_task;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=BINARY"
        }
)
@ActiveProfiles("test")
class RunTaskServiceIntegrationTest {

    private static final String CHECKSUM = "sha256:" + "b".repeat(64);

    @Autowired
    private RunTaskService service;

    @Autowired
    private ProjectCatalogService projectCatalogService;

    @Autowired
    private WorkflowPublishService workflowPublishService;

    @Autowired
    private TestRunRepository runRepository;

    @Autowired
    private TestTaskRepository taskRepository;

    @Autowired
    private TaskDependencyRepository dependencyRepository;

    @Autowired
    private TaskAttemptRepository attemptRepository;

    private UUID projectId;
    private UUID targetId;
    private UUID environmentId;
    private UUID workflowId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var project = projectCatalogService.createProject(new CreateProjectCommand(
                "Run 聚合项目-" + suffix,
                "run-aggregate-" + suffix
        ));
        var target = projectCatalogService.createTarget(project.id(), new CreateTargetCommand(
                "HTTP Target",
                TargetType.HTTP_SERVICE
        ));
        var environment = projectCatalogService.createEnvironment(target.id(), new CreateEnvironmentCommand(
                "Test-" + suffix,
                "https://run-" + suffix + ".example.test",
                Map.of("locale", "zh-CN"),
                Map.of()
        ));
        projectId = project.id();
        targetId = target.id();
        environmentId = environment.id();
        workflowId = id("workflow-" + suffix);
        workflowPublishService.publish(workflowInput(workflowId));
    }

    @Test
    void shouldPersistOwnedTasksDependenciesAndMakeCreationRetrySafe() {
        CreateRunCommand command = command(UUID.randomUUID(), 5);

        var created = service.createRun(command);
        var retried = service.createRun(command);

        assertThat(retried.id()).isEqualTo(created.id());
        assertThat(created.state()).isEqualTo(RunState.QUEUED);
        assertThat(created.tasks()).hasSize(3);
        assertThat(created.taskCounts()).containsEntry(TaskState.QUEUED, 2L)
                .containsEntry(TaskState.WAITING_DEPENDENCY, 1L);
        assertThat(created.tasks()).allSatisfy(task -> assertThat(task.runId()).isEqualTo(created.id()));
        assertThat(created.tasks()).extracting(task -> task.sequenceNo()).containsExactly(0, 1, 2);
        assertThat(created.tasks()).filteredOn(task -> task.platform() != null).singleElement()
                .satisfies(task -> {
                    assertThat(task.platform()).isEqualTo("linux");
                    assertThat(task.requiredFeatures()).containsExactly("docker", "network");
                });
        assertThat(dependencyRepository.countByRunId(created.id())).isEqualTo(1);
        assertThat(taskRepository.countByRunId(created.id())).isEqualTo(3);
        assertThat(runRepository.findByRequestKey(command.requestKey())).isPresent();
        assertThat(created.tasks().stream().flatMap(task -> task.dependencies().stream()))
                .allSatisfy(dependency -> {
                    assertThat(created.tasks()).extracting(task -> task.id())
                            .contains(dependency.predecessorTaskId(), dependency.successorTaskId());
                    assertThat(dependency.condition()).isEqualTo(DependencyCondition.ON_SUCCESS);
                });

        assertThatThrownBy(() -> service.createRun(command(command.requestKey(), 6)))
                .isInstanceOf(RunIdempotencyConflictException.class)
                .hasMessageContaining("相同 requestKey");
        assertThat(taskRepository.countByRunId(created.id())).isEqualTo(3);
    }

    @Test
    void shouldInheritRequestedPlatformWhenCasePlatformIsMissingOrAny() {
        UUID platformWorkflowId = id("platform-inheritance-" + UUID.randomUUID());
        workflowPublishService.publish(platformWorkflowInput(platformWorkflowId, Map.of(), Map.of("platform", "ANY")));
        CreateRunCommand command = command(platformWorkflowId, "WINDOWS");

        var created = service.createRun(command);

        assertThat(created.tasks()).hasSize(2)
                .allSatisfy(task -> assertThat(task.platform()).isEqualTo("WINDOWS"));
    }

    @Test
    void shouldRejectExplicitCasePlatformThatConflictsWithTestJob() {
        UUID platformWorkflowId = id("platform-conflict-" + UUID.randomUUID());
        workflowPublishService.publish(platformWorkflowInput(
                platformWorkflowId,
                Map.of("platform", "ANDROID"),
                Map.of("platform", "ANY")
        ));

        assertThatThrownBy(() -> service.createRun(command(platformWorkflowId, "WINDOWS")))
                .isInstanceOf(RunValidationException.class)
                .hasMessageContaining("Case 平台 ANDROID 与 Test Job 平台 WINDOWS 不匹配");
    }

    @Test
    void shouldReturnAttemptsInsideTheirOwningTaskTimeline() {
        var created = service.createRun(command(UUID.randomUUID(), 5));
        var task = created.tasks().getFirst();
        Instant now = Instant.parse("2026-09-18T06:30:00Z");
        TaskAttemptEntity attempt = attemptRepository.saveAndFlush(new TaskAttemptEntity(
                UUID.randomUUID(),
                task.id(),
                1,
                "worker-1",
                UUID.randomUUID(),
                now.plusSeconds(30),
                now
        ));

        var queried = service.getRun(created.id());
        var owningTask = queried.tasks().stream()
                .filter(candidate -> candidate.id().equals(task.id()))
                .findFirst()
                .orElseThrow();

        assertThat(owningTask.attempts()).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo(attempt.getId());
            assertThat(view.taskId()).isEqualTo(task.id());
            assertThat(view.attemptNo()).isEqualTo(1);
            assertThat(view.workerId()).isEqualTo("worker-1");
            assertThat(view.version()).isZero();
        });
    }

    @Test
    void shouldFreezeDefaultBranchAndPerTaskCommitOverride() throws Exception {
        Path repository = repositoryRoot();
        String branch = git(repository, "branch", "--show-current");
        String head = git(repository, "rev-parse", "HEAD");
        String previous = git(repository, "rev-parse", "HEAD~1");
        projectCatalogService.updateTarget(targetId, new CreateTargetCommand(
                "HTTP Target", TargetType.HTTP_SERVICE, repository.toString(), branch
        ));
        var published = workflowPublishService.get(workflowId, 1);
        UUID overriddenNode = published.compiledSnapshot().nodes().get(1).id();

        CreateRunCommand command = new CreateRunCommand(
                projectId,
                targetId,
                environmentId,
                workflowId,
                1,
                5,
                3,
                UUID.randomUUID(),
                null,
                Map.of(overriddenNode, new RevisionSelector(RevisionType.COMMIT, previous))
        );
        var created = service.createRun(command);

        assertThat(created.tasks()).allSatisfy(task -> assertThat(task.targetRevision()).isNotNull());
        assertThat(created.tasks().stream()
                .filter(task -> task.workflowNodeId().equals(overriddenNode))
                .findFirst().orElseThrow().targetRevision())
                .satisfies(revision -> {
                    assertThat(revision.requestedType()).isEqualTo(RevisionType.COMMIT);
                    assertThat(revision.resolvedCommit()).isEqualTo(previous);
                });
        assertThat(created.tasks().stream()
                .filter(task -> !task.workflowNodeId().equals(overriddenNode)))
                .allSatisfy(task -> {
                    assertThat(task.targetRevision().requestedType()).isEqualTo(RevisionType.DEFAULT_BRANCH);
                    assertThat(task.targetRevision().resolvedCommit()).isEqualTo(head);
                });
    }

    @Test
    void shouldReturnOneAggregateForConcurrentIdenticalCreation() throws Exception {
        CreateRunCommand command = command(UUID.randomUUID(), 4);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = CompletableFuture.supplyAsync(() -> awaitAndCreate(start, command), executor);
            var second = CompletableFuture.supplyAsync(() -> awaitAndCreate(start, command), executor);
            start.countDown();

            var firstView = first.get(10, TimeUnit.SECONDS);
            var secondView = second.get(10, TimeUnit.SECONDS);
            assertThat(secondView.id()).isEqualTo(firstView.id());
            assertThat(taskRepository.countByRunId(firstView.id())).isEqualTo(3);
            assertThat(dependencyRepository.countByRunId(firstView.id())).isEqualTo(1);
        }
    }

    @Test
    void shouldPropagateCancellationIntentAndConvergeAfterRunningTaskStops() {
        var created = service.createRun(command(UUID.randomUUID(), 3));
        TestTaskEntity running = taskRepository.findAllByRunIdOrderBySequenceNoAsc(created.id()).stream()
                .filter(task -> task.getState() == TaskState.QUEUED)
                .findFirst()
                .orElseThrow();
        Instant now = Instant.parse("2026-09-18T06:00:00Z");
        running.transitionTo(TaskState.DISPATCHED, now);
        running.transitionTo(TaskState.RUNNING, now);
        taskRepository.saveAndFlush(running);

        UUID cancelKey = UUID.randomUUID();
        var cancelling = service.requestCancellation(
                created.id(),
                new CancelRunCommand(cancelKey, "用户停止演示")
        );

        assertThat(cancelling.state()).isEqualTo(RunState.CANCELLING);
        assertThat(cancelling.version()).isGreaterThan(created.version());
        assertThat(cancelling.tasks()).allSatisfy(task -> {
            if (task.id().equals(running.getId())) {
                assertThat(task.state()).isEqualTo(TaskState.RUNNING);
            } else {
                assertThat(task.state()).isEqualTo(TaskState.CANCELLED);
            }
            assertThat(task.cancellationRequestedAt()).isNotNull();
            assertThat(task.cancellationReason()).isEqualTo("用户停止演示");
            assertThat(task.version()).isPositive();
        });
        assertThat(service.requestCancellation(
                created.id(),
                new CancelRunCommand(cancelKey, "用户停止演示")
        ).state()).isEqualTo(RunState.CANCELLING);

        TestTaskEntity reloadedRunning = taskRepository.findById(running.getId()).orElseThrow();
        reloadedRunning.transitionTo(TaskState.CANCELLED, Instant.now());
        taskRepository.saveAndFlush(reloadedRunning);
        var cancelled = service.refreshAggregate(created.id());
        assertThat(cancelled.state()).isEqualTo(RunState.CANCELLED);
        assertThat(cancelled.completedAt()).isNotNull();

        TestRunEntity terminal = runRepository.findById(created.id()).orElseThrow();
        assertThatThrownBy(() -> terminal.applyAggregateState(RunState.SUCCEEDED, Instant.now()))
                .isInstanceOf(RunStateConflictException.class)
                .hasMessageContaining("终态不可改变");
    }

    @Test
    void shouldPersistCompletedWithWarningsWhenOnlyOptionalTaskFails() {
        var created = service.createRun(command(UUID.randomUUID(), 2));
        List<TestTaskEntity> tasks = taskRepository.findAllByRunIdOrderBySequenceNoAsc(created.id());
        for (TestTaskEntity task : tasks) {
            if (task.getState() == TaskState.WAITING_DEPENDENCY) {
                task.transitionTo(TaskState.QUEUED, Instant.now());
            }
            task.transitionTo(TaskState.DISPATCHED, Instant.now());
            task.transitionTo(TaskState.RUNNING, Instant.now());
            task.transitionTo(task.isRequired() ? TaskState.SUCCEEDED : TaskState.FAILED, Instant.now());
        }
        taskRepository.saveAllAndFlush(tasks);

        var aggregate = service.refreshAggregate(created.id());

        assertThat(aggregate.state()).isEqualTo(RunState.COMPLETED_WITH_WARNINGS);
        assertThat(service.getRun(created.id()).state()).isEqualTo(RunState.COMPLETED_WITH_WARNINGS);
    }

    @Test
    void shouldAllowGlobalEnvironmentAcrossTargets() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var foreignTarget = projectCatalogService.createTarget(projectId, new CreateTargetCommand(
                "Foreign Target-" + suffix,
                TargetType.WEB
        ));
        var foreignEnvironment = projectCatalogService.createEnvironment(
                foreignTarget.id(),
                new CreateEnvironmentCommand(
                        "Foreign-" + suffix,
                        "https://foreign-" + suffix + ".example.test",
                        Map.of(),
                        Map.of()
                )
        );
        CreateRunCommand command = new CreateRunCommand(
                projectId,
                targetId,
                foreignEnvironment.id(),
                workflowId,
                1,
                5,
                3,
                UUID.randomUUID()
        );

        assertThat(service.createRun(command).environmentId()).isEqualTo(foreignEnvironment.id());
    }

    private io.testforge.runorchestrator.run.model.RunView awaitAndCreate(
            CountDownLatch start,
            CreateRunCommand command
    ) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发创建未按时开始");
            }
            return service.createRun(command);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private CreateRunCommand command(UUID requestKey, int priority) {
        return new CreateRunCommand(
                projectId,
                targetId,
                environmentId,
                workflowId,
                1,
                priority,
                3,
                requestKey
        );
    }

    private CreateRunCommand command(UUID workflowId, String requestedPlatform) {
        return new CreateRunCommand(
                projectId, targetId, environmentId, workflowId, 1,
                5, 3, 3, 3, UUID.randomUUID(), null, Map.of(), null,
                null, 0, null, null, requestedPlatform
        );
    }

    private WorkflowPublishInput platformWorkflowInput(
            UUID workflowId,
            Map<String, Object> firstParameters,
            Map<String, Object> secondParameters
    ) {
        UUID caseA = id(workflowId + "-case-a");
        UUID caseB = id(workflowId + "-case-b");
        UUID nodeA = id(workflowId + "-node-a");
        UUID nodeB = id(workflowId + "-node-b");
        CaseReferenceInput referenceA = reference(caseA, "cases/platform-a.py", firstParameters);
        CaseReferenceInput referenceB = reference(caseB, "cases/platform-b.py", secondParameters);
        return new WorkflowPublishInput(
                workflowId,
                projectId,
                targetId,
                1,
                new WorkflowGraphInput(
                        List.of(node(nodeA, caseA, true), node(nodeB, caseB, true)),
                        List.of()
                ),
                new ReferenceCatalogInput(
                        Map.of(referenceA.key(), referenceA, referenceB.key(), referenceB),
                        Map.of(),
                        Map.of()
                )
        );
    }

    private WorkflowPublishInput workflowInput(UUID workflowId) {
        UUID caseA = id(workflowId + "-case-a");
        UUID caseB = id(workflowId + "-case-b");
        UUID caseC = id(workflowId + "-case-c");
        UUID nodeA = id(workflowId + "-node-a");
        UUID nodeB = id(workflowId + "-node-b");
        UUID nodeC = id(workflowId + "-node-c");
        CaseReferenceInput referenceA = reference(
                caseA,
                "cases/a.py",
                Map.of("platform", "linux", "requiredFeatures", List.of("network", "docker", "network"))
        );
        CaseReferenceInput referenceB = reference(caseB, "cases/b.py", Map.of());
        CaseReferenceInput referenceC = reference(caseC, "cases/c.py", Map.of());
        return new WorkflowPublishInput(
                workflowId,
                projectId,
                targetId,
                1,
                new WorkflowGraphInput(
                        List.of(
                                node(nodeA, caseA, true),
                                node(nodeB, caseB, true),
                                node(nodeC, caseC, false)
                        ),
                        List.of(new WorkflowEdgeInput(nodeA, nodeB, DependencyCondition.ON_SUCCESS))
                ),
                new ReferenceCatalogInput(
                        Map.of(
                                referenceA.key(), referenceA,
                                referenceB.key(), referenceB,
                                referenceC.key(), referenceC
                        ),
                        Map.of(),
                        Map.of()
                )
        );
    }

    private WorkflowNodeInput node(UUID nodeId, UUID caseId, boolean required) {
        return new WorkflowNodeInput(
                nodeId,
                PublishNodeType.CASE,
                caseId,
                1,
                required,
                null,
                Map.of()
        );
    }

    private CaseReferenceInput reference(UUID caseId, String sourceRef, Map<String, Object> parameters) {
        return new CaseReferenceInput(
                caseId,
                1,
                projectId,
                targetId,
                ExecutableNodeType.CASE,
                id(caseId + "-script"),
                "pytest-http",
                sourceRef,
                CHECKSUM,
                30,
                parameters
        );
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve(".git"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("无法定位 TestForge Git 仓库");
        }
        return candidate;
    }

    private String git(Path repository, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repository.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ProjectCatalogConfig.class, RunTaskPersistenceConfig.class})
    static class TestApplication {
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {
            PublishedWorkflowVersionEntity.class,
            TestRunEntity.class,
            TestTaskEntity.class,
            TaskDependencyEntity.class,
            TaskAttemptEntity.class
    })
    @EnableJpaRepositories(basePackageClasses = {
            PublishedWorkflowVersionRepository.class,
            TestRunRepository.class,
            TestTaskRepository.class,
            TaskDependencyRepository.class,
            TaskAttemptRepository.class
    })
    static class RunTaskPersistenceConfig {

        @Bean
        WorkflowPublishService workflowPublishService(
                PublishedWorkflowVersionRepository repository,
                ObjectMapper objectMapper
        ) {
            return new WorkflowPublishService(repository, objectMapper);
        }

        @Bean
        RunTaskService runTaskService(
                TestRunRepository runRepository,
                TestTaskRepository taskRepository,
                TaskDependencyRepository dependencyRepository,
                TaskAttemptRepository attemptRepository,
                ProjectCatalogService projectCatalogService,
                WorkflowPublishService workflowPublishService,
                ObjectMapper objectMapper
        ) {
            return new RunTaskService(
                    runRepository,
                    taskRepository,
                    dependencyRepository,
                    attemptRepository,
                    projectCatalogService,
                    workflowPublishService,
                    objectMapper
            );
        }
    }
}
