package io.testforge.runorchestrator.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import io.testforge.runorchestrator.comparison.service.ComparisonRunService;
import io.testforge.runorchestrator.run.model.RunView;
import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.run.service.RunIdempotencyConflictException;
import io.testforge.runorchestrator.run.service.RunStateConflictException;
import io.testforge.runorchestrator.run.service.RunTaskService;
import io.testforge.testjob.service.TestJobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TestJobLaunchServiceTest {

    @Test
    void returnsTheFirstRunBeforeRevalidatingMovingExternalReferences() {
        UUID jobId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        RunView existing = mock(RunView.class);
        when(existing.testJobId()).thenReturn(jobId);
        RunTaskService runs = mock(RunTaskService.class);
        when(runs.findByRequestKey(requestKey)).thenReturn(Optional.of(existing));
        @SuppressWarnings("unchecked")
        ObjectProvider<TestJobService> jobs = mock(ObjectProvider.class);
        var service = new TestJobLaunchService(jobs, runs, new ObjectMapper(),
                mock(ComparisonRunService.class), mock(ProjectCatalogService.class));

        assertThat(service.launch(jobId, requestKey)).isSameAs(existing);
        verifyNoInteractions(jobs);
        verify(runs, never()).createRun(any());
    }

    @Test
    void rejectsARequestKeyOwnedByAnotherTestJob() {
        UUID requestKey = UUID.randomUUID();
        RunView existing = mock(RunView.class);
        when(existing.testJobId()).thenReturn(UUID.randomUUID());
        RunTaskService runs = mock(RunTaskService.class);
        when(runs.findByRequestKey(requestKey)).thenReturn(Optional.of(existing));
        @SuppressWarnings("unchecked")
        ObjectProvider<TestJobService> jobs = mock(ObjectProvider.class);
        var service = new TestJobLaunchService(jobs, runs, new ObjectMapper(),
                mock(ComparisonRunService.class), mock(ProjectCatalogService.class));

        assertThatThrownBy(() -> service.launch(UUID.randomUUID(), requestKey))
                .isInstanceOf(RunIdempotencyConflictException.class);
        verifyNoInteractions(jobs);
    }

    @Test
    void rejectsASecondActiveAttemptForTheSameTask() {
        UUID jobId = UUID.randomUUID();
        RunView active = mock(RunView.class);
        when(active.state()).thenReturn(RunState.RUNNING);
        RunTaskService runs = mock(RunTaskService.class);
        when(runs.findByRequestKey(any())).thenReturn(Optional.empty());
        when(runs.listRunsByTestJob(jobId)).thenReturn(List.of(active));
        @SuppressWarnings("unchecked")
        ObjectProvider<TestJobService> jobs = mock(ObjectProvider.class);
        var service = new TestJobLaunchService(jobs, runs, new ObjectMapper(),
                mock(ComparisonRunService.class), mock(ProjectCatalogService.class));

        assertThatThrownBy(() -> service.launch(jobId, UUID.randomUUID()))
                .isInstanceOf(RunStateConflictException.class)
                .hasMessageContaining("活动执行 Attempt");
        verifyNoInteractions(jobs);
        verify(runs, never()).createRun(any());
    }
}
