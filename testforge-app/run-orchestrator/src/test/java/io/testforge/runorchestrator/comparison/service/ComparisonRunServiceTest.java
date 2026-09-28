package io.testforge.runorchestrator.comparison.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.workflow.compile.model.ExecutableNodeType;
import io.testforge.casecatalog.workflow.compile.model.PublishNodeType;
import io.testforge.projectcatalog.revision.RevisionSelector;
import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.runorchestrator.comparison.entity.ComparisonRunEntity;
import io.testforge.runorchestrator.comparison.model.CreateComparisonRunCommand;
import io.testforge.runorchestrator.comparison.repo.ComparisonRunRepository;
import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.run.model.RunView;
import io.testforge.runorchestrator.run.service.RunTaskService;
import io.testforge.runorchestrator.task.model.ResourceMode;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.model.TaskTargetRevision;
import io.testforge.runorchestrator.task.model.TaskView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComparisonRunServiceTest {
    @Test
    void createsOnePersistentGroupAndReturnsSamePairForIdempotentRetry() {
        ComparisonRunRepository repository = mock(ComparisonRunRepository.class);
        RunTaskService runs = mock(RunTaskService.class);
        ComparisonRunService service = new ComparisonRunService(repository, runs, new ObjectMapper().findAndRegisterModules());
        UUID baselineId = UUID.randomUUID(), candidateId = UUID.randomUUID();
        RunView baseline = run(baselineId, "1".repeat(40));
        RunView candidate = run(candidateId, "2".repeat(40));
        when(runs.createRun(any())).thenReturn(baseline, candidate);
        when(runs.getRun(baselineId)).thenReturn(baseline);
        when(runs.getRun(candidateId)).thenReturn(candidate);

        UUID requestKey = UUID.randomUUID();
        CreateComparisonRunCommand command = command(requestKey);
        ArgumentCaptor<ComparisonRunEntity> saved = ArgumentCaptor.forClass(ComparisonRunEntity.class);
        when(repository.findByRequestKey(requestKey)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(saved.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        var created = service.create(command);

        when(repository.findByRequestKey(requestKey)).thenReturn(Optional.of(saved.getValue()));
        var retried = service.create(command);

        assertThat(retried.id()).isEqualTo(created.id());
        assertThat(created.baselineRun().id()).isEqualTo(baselineId);
        assertThat(created.candidateRun().id()).isEqualTo(candidateId);
        verify(runs, times(2)).createRun(any());
        verify(runs).assignComparisonGroup(baselineId, created.id());
        verify(runs).assignComparisonGroup(candidateId, created.id());
    }

    private CreateComparisonRunCommand command(UUID requestKey) {
        return new CreateComparisonRunCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, 5, 2, 2, 1, requestKey,
                new RevisionSelector(RevisionType.BRANCH, "baseline"),
                new RevisionSelector(RevisionType.BRANCH, "candidate"), null);
    }

    private RunView run(UUID id, String commit) {
        Instant now = Instant.now();
        TaskTargetRevision revision = new TaskTargetRevision("https://example.test/repo.git", RevisionType.BRANCH,
                "main", commit, now);
        TaskView task = new TaskView(UUID.randomUUID(), id, 0, UUID.randomUUID(), "case.py", PublishNodeType.CASE,
                ExecutableNodeType.CASE, UUID.randomUUID(), UUID.randomUUID(), 1, true, "pytest-http", null,
                List.of(), ResourceMode.PROCESS_POOL, null, revision, "case.py", "sha256:" + "a".repeat(64),
                30, Map.of(), TaskState.QUEUED, 0, null, null, null, null, null, now, now, null, List.of(), List.of());
        return new RunView(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "sha256:" + "b".repeat(64), RunState.QUEUED, 5, 2, 2, 1, UUID.randomUUID(), 0,
                null, null, now, now, null, Map.of(TaskState.QUEUED, 1L), List.of(task));
    }
}
