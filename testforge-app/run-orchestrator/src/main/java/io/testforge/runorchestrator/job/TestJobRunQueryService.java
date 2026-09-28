package io.testforge.runorchestrator.job;

import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.run.model.RunView;
import io.testforge.runorchestrator.run.service.RunTaskService;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.testjob.model.TestJobView;
import io.testforge.testjob.service.TestJobService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class TestJobRunQueryService {
    private final ObjectProvider<TestJobService> jobs;
    private final RunTaskService runs;
    private final ProjectCatalogService projects;
    public TestJobRunQueryService(ObjectProvider<TestJobService> jobs, RunTaskService runs,
                                  ProjectCatalogService projects) {
        this.jobs = jobs; this.runs = runs; this.projects = projects;
    }

    public List<TestTaskExecutionSummary> summaries(UUID projectId) {
        return jobs().list(projectId).stream().map(this::summary)
                .sorted(Comparator.comparing(TestTaskExecutionSummary::activeAttemptCount).reversed()
                        .thenComparing(item -> item.lastExecutedAt() == null ? Instant.EPOCH : item.lastExecutedAt(), Comparator.reverseOrder()))
                .toList();
    }

    public List<RunView> runs(UUID testJobId) {
        jobs().get(testJobId);
        return runs.listRunsByTestJob(testJobId);
    }

    private TestTaskExecutionSummary summary(TestJobView job) {
        List<RunView> history = runs.listRunsByTestJob(job.id());
        long active = history.stream().filter(run -> !run.state().isTerminal()).count();
        RunView latest = history.isEmpty() ? null : history.getFirst();
        int progress = latest == null || latest.tasks().isEmpty() ? 0 : (int) Math.round(100.0
                * latest.taskCounts().entrySet().stream().filter(entry -> terminal(entry.getKey()))
                .mapToLong(java.util.Map.Entry::getValue).sum() / latest.tasks().size());
        String projectName = projects.requireProjectView(job.projectId()).name();
        String waitingExecutor = latest == null ? null : latest.tasks().stream()
                .filter(task -> task.state() == TaskState.QUEUED)
                .map(task -> task.runner())
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        return new TestTaskExecutionSummary(job.id(), job.projectId(), projectName, job.name(), job.code(),
                job.state().name(), history.size(), active, latest == null ? null : latest.id(),
                latest == null ? null : latest.state(), latest == null ? null : phase(latest), progress,
                latest == null ? null : latest.createdAt(),
                job.resolvedCommit(), job.commitMessage(), job.workflowVersion(), waitingExecutor);
    }

    private String phase(RunView run) {
        if (run.taskCounts().getOrDefault(TaskState.WAITING_DEPLOYMENT, 0L) > 0) return "WAITING_DEPLOYMENT";
        if (run.taskCounts().getOrDefault(TaskState.WAITING_DEPENDENCY, 0L) > 0) return "WAITING_DEPENDENCY";
        if (run.taskCounts().getOrDefault(TaskState.QUEUED, 0L) > 0) return "WAITING_RESOURCE";
        return run.state().name();
    }

    private boolean terminal(TaskState state) {
        return state == TaskState.SUCCEEDED || state == TaskState.FAILED || state == TaskState.BLOCKED
                || state == TaskState.CANCELLED || state == TaskState.TIMEOUT;
    }

    private TestJobService jobs() {
        return jobs.getIfAvailable(() -> {
            throw new IllegalStateException("Test Job 模块未启用");
        });
    }

    public record TestTaskExecutionSummary(UUID taskId, UUID projectId, String projectName, String name, String code,
                                           String taskState, int attemptCount, long activeAttemptCount,
                                           UUID currentAttemptId, RunState currentAttemptState,
                                           String currentAttemptPhase, int progress, Instant lastExecutedAt, String resolvedCommit,
                                           String commitMessage, int workflowVersion, String waitingExecutor) { }
}
