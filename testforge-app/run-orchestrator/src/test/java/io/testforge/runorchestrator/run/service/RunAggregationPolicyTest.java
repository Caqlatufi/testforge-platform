package io.testforge.runorchestrator.run.service;

import io.testforge.casecatalog.workflow.compile.model.CompiledNode;
import io.testforge.casecatalog.workflow.compile.model.ExecutableNodeType;
import io.testforge.casecatalog.workflow.compile.model.PublishNodeType;
import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.task.entity.TestTaskEntity;
import io.testforge.runorchestrator.task.model.TaskState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunAggregationPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-18T05:00:00Z");
    private final RunAggregationPolicy policy = new RunAggregationPolicy();

    @Test
    void shouldAggregateSuccessRequiredFailureAndOptionalWarning() {
        TestTaskEntity requiredSuccess = terminalTask("required-success", true, TaskState.SUCCEEDED);
        TestTaskEntity requiredFailure = terminalTask("required-failure", true, TaskState.FAILED);
        TestTaskEntity optionalFailure = terminalTask("optional-failure", false, TaskState.FAILED);

        assertThat(policy.determine(false, List.of(requiredSuccess))).isEqualTo(RunState.SUCCEEDED);
        assertThat(policy.determine(false, List.of(requiredSuccess, optionalFailure)))
                .isEqualTo(RunState.COMPLETED_WITH_WARNINGS);
        assertThat(policy.determine(false, List.of(requiredSuccess, requiredFailure, optionalFailure)))
                .isEqualTo(RunState.FAILED);
    }

    @Test
    void shouldKeepActiveRunsNonTerminalAndLetCancellationDominateConclusion() {
        TestTaskEntity queued = task("queued", true, TaskState.QUEUED);
        TestTaskEntity running = task("running", true, TaskState.QUEUED);
        running.transitionTo(TaskState.DISPATCHED, NOW);
        running.transitionTo(TaskState.RUNNING, NOW);

        assertThat(policy.determine(false, List.of(queued))).isEqualTo(RunState.QUEUED);
        assertThat(policy.determine(false, List.of(queued, running))).isEqualTo(RunState.RUNNING);
        assertThat(policy.determine(true, List.of(queued, running))).isEqualTo(RunState.CANCELLING);

        queued.requestCancellation("用户取消", NOW);
        running.requestCancellation("用户取消", NOW);
        running.transitionTo(TaskState.CANCELLED, NOW);
        assertThat(policy.determine(true, List.of(queued, running))).isEqualTo(RunState.CANCELLED);
    }

    private TestTaskEntity terminalTask(String name, boolean required, TaskState terminalState) {
        TestTaskEntity task = task(name, required, TaskState.QUEUED);
        task.transitionTo(TaskState.DISPATCHED, NOW);
        task.transitionTo(TaskState.RUNNING, NOW);
        task.transitionTo(terminalState, NOW);
        return task;
    }

    private TestTaskEntity task(String name, boolean required, TaskState state) {
        UUID nodeId = id(name + "-node");
        CompiledNode node = new CompiledNode(
                nodeId,
                "workflow/test/" + name,
                PublishNodeType.CASE,
                ExecutableNodeType.CASE,
                id(name + "-case"),
                id(name + "-script"),
                1,
                required,
                "pytest-http",
                "cases/" + name + ".py",
                "sha256:" + "a".repeat(64),
                30,
                Map.of()
        );
        return new TestTaskEntity(
                id(name + "-task"),
                id("run"),
                0,
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
}
