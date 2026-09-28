package io.testforge.dispatcher.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.CaseCatalogConfig;
import io.testforge.casecatalog.workflow.compile.model.CaseReferenceInput;
import io.testforge.casecatalog.workflow.compile.model.ExecutableNodeType;
import io.testforge.casecatalog.workflow.compile.model.PublishNodeType;
import io.testforge.casecatalog.workflow.compile.model.ReferenceCatalogInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowGraphInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowNodeInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowPublishInput;
import io.testforge.casecatalog.workflow.compile.service.WorkflowPublishService;
import io.testforge.dispatcher.DispatcherConfig;
import io.testforge.dispatcher.outbox.repo.OutboxEventRepository;
import io.testforge.dispatcher.reliability.lease.AttemptLeaseService;
import io.testforge.dispatcher.reliability.retry.RetryCoordinator;
import io.testforge.dispatcher.reliability.retry.RetryAction;
import io.testforge.dispatcher.reliability.retry.RetryConvergenceOutcome;
import io.testforge.dispatcher.reliability.retry.RetryEvent;
import io.testforge.projectcatalog.ProjectCatalogConfig;
import io.testforge.projectcatalog.model.CreateEnvironmentCommand;
import io.testforge.projectcatalog.model.CreateProjectCommand;
import io.testforge.projectcatalog.model.CreateTargetCommand;
import io.testforge.projectcatalog.model.TargetType;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import io.testforge.runorchestrator.RunOrchestratorConfig;
import io.testforge.runorchestrator.model.attempt.AttemptState;
import io.testforge.runorchestrator.run.model.CreateRunCommand;
import io.testforge.runorchestrator.run.model.CancelRunCommand;
import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.run.service.RunTaskService;
import io.testforge.runorchestrator.service.quota.RunConcurrencyQuotaService;
import io.testforge.runorchestrator.task.model.TaskState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = LeaseRecoveryIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:lease_recovery;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=BINARY",
                "testforge.dispatcher.reliability.enabled=false",
                "testforge.dispatcher.reliability.max-attempts=3",
                "testforge.dispatcher.reliability.initial-backoff=5s",
                "testforge.dispatcher.reliability.max-backoff=1m"
        }
)
class LeaseRecoveryIntegrationTest {

    private static final String CHECKSUM = "sha256:" + "d".repeat(64);

    @Autowired private ProjectCatalogService projectCatalogService;
    @Autowired private WorkflowPublishService workflowPublishService;
    @Autowired private RunTaskService runTaskService;
    @Autowired private RunConcurrencyQuotaService quotaService;
    @Autowired private AttemptLeaseService leaseService;
    @Autowired private AttemptRecoveryService recoveryService;
    @Autowired private RetryCoordinator retryCoordinator;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private MutableClock clock;

    @Test
    void shouldRecoverAndConvergeAfterWorkerLossForThreeConsecutiveRuns() {
        Fixture fixture = createFixture();

        for (int round = 1; round <= 3; round++) {
            var run = runTaskService.createRun(new CreateRunCommand(
                    fixture.projectId(), fixture.targetId(), fixture.environmentId(),
                    fixture.workflowId(), 1, 5, 1, UUID.randomUUID()
            ));
            UUID taskId = run.tasks().getFirst().id();
            quotaService.acquire(run.id(), taskId, clock.instant());

            UUID firstAttemptId = UUID.randomUUID();
            var firstLease = leaseService.issue(firstAttemptId, taskId, "worker-dead-" + round);
            assertThat(firstLease.heartbeatInterval()).isEqualTo(Duration.ofSeconds(5));

            clock.advance(Duration.ofSeconds(5));
            var heartbeat = leaseService.heartbeat(
                    firstAttemptId, "worker-dead-" + round, firstLease.leaseToken()
            );
            assertThat(heartbeat.leaseUntil()).isEqualTo(clock.instant().plusSeconds(15));

            clock.advance(Duration.ofSeconds(11));
            assertThat(recoveryService.recoverOnce().leaseReap().lostCount()).isZero();
            clock.advance(Duration.ofSeconds(5));
            var recovered = recoveryService.recoverOnce();

            assertThat(recovered.leaseReap().lostCount()).isEqualTo(1);
            assertThat(recovered.lostConverged()).isEqualTo(1);
            var afterLoss = runTaskService.getRun(run.id()).tasks().getFirst();
            assertThat(afterLoss.state()).isEqualTo(TaskState.QUEUED);
            assertThat(afterLoss.attempts()).hasSize(1);
            assertThat(afterLoss.attempts().getFirst().state()).isEqualTo(AttemptState.LOST);

            var retryEvent = outboxRepository.findByEventKey(
                    "task-ready:" + taskId + ":attempt:2"
            ).orElseThrow().toView();
            assertThat(retryEvent.availableAt()).isEqualTo(clock.instant().plusSeconds(5));

            assertThat(quotaService.acquire(run.id(), taskId, clock.instant()).outcome())
                    .isEqualTo(io.testforge.runorchestrator.service.quota.QuotaReservation.Outcome.RETRY_NOT_READY);

            clock.advance(Duration.ofSeconds(5));
            quotaService.acquire(run.id(), taskId, clock.instant());
            UUID secondAttemptId = UUID.randomUUID();
            leaseService.issue(secondAttemptId, taskId, "worker-live-" + round);
            retryCoordinator.converge(RetryEvent.completed(
                    taskId, secondAttemptId, 2, clock.instant()
            ));

            var completedRun = runTaskService.getRun(run.id());
            var completed = completedRun.tasks().getFirst();
            assertThat(completedRun.state()).isEqualTo(RunState.SUCCEEDED);
            assertThat(completed.state()).isEqualTo(TaskState.SUCCEEDED);
            assertThat(completed.attempts()).extracting(attempt -> attempt.state())
                    .containsExactly(AttemptState.LOST, AttemptState.SUCCEEDED);
        }
    }

    @Test
    void shouldLetPersistedCancellationBeatALateCompletion() {
        Fixture fixture = createFixture();
        var run = createRun(fixture);
        UUID taskId = run.tasks().getFirst().id();
        quotaService.acquire(run.id(), taskId, clock.instant());
        UUID attemptId = UUID.randomUUID();
        leaseService.issue(attemptId, taskId, "worker-cancel");

        runTaskService.requestCancellation(
                run.id(), new CancelRunCommand(UUID.randomUUID(), "用户取消")
        );
        var result = retryCoordinator.converge(
                RetryEvent.completed(taskId, attemptId, 1, clock.instant())
        );

        assertThat(result.outcome()).isEqualTo(RetryConvergenceOutcome.APPLIED);
        assertThat(result.action()).isEqualTo(RetryAction.CANCEL);
        var cancelled = runTaskService.getRun(run.id());
        assertThat(cancelled.state()).isEqualTo(RunState.CANCELLED);
        assertThat(cancelled.tasks().getFirst().state()).isEqualTo(TaskState.CANCELLED);
        assertThat(cancelled.tasks().getFirst().attempts().getFirst().state())
                .isEqualTo(AttemptState.CANCELLED);
    }

    @Test
    void shouldLetHardTimeoutBeatALateCompletionAndRequeueWithBackoff() {
        Fixture fixture = createFixture();
        var run = createRun(fixture);
        UUID taskId = run.tasks().getFirst().id();
        quotaService.acquire(run.id(), taskId, clock.instant());
        UUID attemptId = UUID.randomUUID();
        var lease = leaseService.issue(attemptId, taskId, "worker-slow");

        for (int heartbeat = 0; heartbeat < 10; heartbeat++) {
            clock.advance(Duration.ofSeconds(5));
            leaseService.heartbeat(attemptId, "worker-slow", lease.leaseToken());
        }
        clock.advance(Duration.ofSeconds(10));
        var timeout = recoveryService.recoverOnce();

        assertThat(timeout.leaseReap().lostCount()).isZero();
        assertThat(timeout.timedOutConverged()).isEqualTo(1);
        var afterTimeout = runTaskService.getRun(run.id()).tasks().getFirst();
        assertThat(afterTimeout.state()).isEqualTo(TaskState.QUEUED);
        assertThat(afterTimeout.attempts().getFirst().state()).isEqualTo(AttemptState.TIMEOUT);

        var late = retryCoordinator.converge(
                RetryEvent.completed(taskId, attemptId, 1, clock.instant().plusSeconds(1))
        );
        assertThat(late.outcome()).isEqualTo(RetryConvergenceOutcome.ALREADY_CONVERGED);
        assertThat(runTaskService.getRun(run.id()).tasks().getFirst().state())
                .isEqualTo(TaskState.QUEUED);
    }

    private io.testforge.runorchestrator.run.model.RunView createRun(Fixture fixture) {
        return runTaskService.createRun(new CreateRunCommand(
                fixture.projectId(), fixture.targetId(), fixture.environmentId(),
                fixture.workflowId(), 1, 5, 1, UUID.randomUUID()
        ));
    }

    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var project = projectCatalogService.createProject(new CreateProjectCommand(
                "Lease 项目-" + suffix, "lease-" + suffix
        ));
        var target = projectCatalogService.createTarget(project.id(), new CreateTargetCommand(
                "HTTP Target", TargetType.HTTP_SERVICE
        ));
        var environment = projectCatalogService.createEnvironment(target.id(), new CreateEnvironmentCommand(
                "Test-" + suffix, "https://lease-" + suffix + ".example.test", Map.of(), Map.of()
        ));
        UUID workflowId = id("workflow-" + suffix);
        UUID caseId = id("case-" + suffix);
        CaseReferenceInput reference = new CaseReferenceInput(
                caseId, 1, project.id(), target.id(), ExecutableNodeType.CASE,
                id("script-" + suffix), "pytest-http", "cases/lease.py", CHECKSUM, 60,
                Map.of("platform", "linux", "requiredFeatures", List.of("network"))
        );
        workflowPublishService.publish(new WorkflowPublishInput(
                workflowId, project.id(), target.id(), 1,
                new WorkflowGraphInput(List.of(new WorkflowNodeInput(
                        id("node-" + suffix), PublishNodeType.CASE, caseId, 1, true, null, Map.of()
                )), List.of()),
                new ReferenceCatalogInput(Map.of(reference.key(), reference), Map.of(), Map.of())
        ));
        return new Fixture(project.id(), target.id(), environment.id(), workflowId);
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Fixture(UUID projectId, UUID targetId, UUID environmentId, UUID workflowId) { }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(
                Instant.parse("2026-09-18T00:00:00Z")
        );

        void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            ProjectCatalogConfig.class,
            CaseCatalogConfig.class,
            RunOrchestratorConfig.class,
            DispatcherConfig.class
    })
    static class TestApplication {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
