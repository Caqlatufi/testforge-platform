package io.testforge.report.service;

import io.testforge.runorchestrator.run.model.RunView;
import io.testforge.runorchestrator.run.service.RunTaskService;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.model.TaskView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunComparisonServiceTest {
    @Test
    void classifiesRegressionAndFix() {
        RunTaskService runs = mock(RunTaskService.class);
        UUID project = UUID.randomUUID();
        UUID workflow = UUID.randomUUID();
        UUID baselineId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID regressed = UUID.randomUUID();
        UUID fixed = UUID.randomUUID();
        when(runs.getRun(baselineId)).thenReturn(run(baselineId, project, workflow, List.of(task(regressed, TaskState.SUCCEEDED), task(fixed, TaskState.FAILED))));
        when(runs.getRun(candidateId)).thenReturn(run(candidateId, project, workflow, List.of(task(regressed, TaskState.FAILED), task(fixed, TaskState.SUCCEEDED))));

        RunComparisonReport report = new RunComparisonService(runs).compare(baselineId, candidateId);

        assertThat(report.summary().regressions()).isEqualTo(1);
        assertThat(report.summary().fixed()).isEqualTo(1);
        assertThat(report.cases()).extracting(RunComparisonReport.CaseComparison::conclusion)
                .containsExactly("REGRESSION", "FIXED");
    }

    private RunView run(UUID id, UUID project, UUID workflow, List<TaskView> tasks) {
        return new RunView(id, project, UUID.randomUUID(), UUID.randomUUID(), workflow, 1, "sha256:" + "a".repeat(64),
                io.testforge.runorchestrator.run.model.RunState.SUCCEEDED, 5, 2, 2, 2, UUID.randomUUID(), 0,
                null, null, Instant.now(), Instant.now(), Instant.now(), Map.of(), tasks);
    }

    private TaskView task(UUID caseId, TaskState state) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        return new TaskView(id, UUID.randomUUID(), 0, UUID.randomUUID(), "source", io.testforge.casecatalog.workflow.compile.model.PublishNodeType.CASE,
                io.testforge.casecatalog.workflow.compile.model.ExecutableNodeType.CASE, caseId, UUID.randomUUID(), 1, true,
                "pytest-http", null, List.of(), io.testforge.runorchestrator.task.model.ResourceMode.PROCESS_POOL, null, null,
                "source.py", "sha256:" + "a".repeat(64), 30, Map.of(), state, 0, null, null, null, null,
                null, now, now, state.isTerminal() ? now : null, List.of(), List.of());
    }
}
