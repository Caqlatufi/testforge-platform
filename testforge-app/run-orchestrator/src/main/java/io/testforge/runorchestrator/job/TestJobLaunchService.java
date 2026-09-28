package io.testforge.runorchestrator.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.projectcatalog.revision.RevisionSelector;
import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.runorchestrator.run.model.CreateRunCommand;
import io.testforge.runorchestrator.run.model.RunView;
import io.testforge.runorchestrator.run.service.RunTaskService;
import io.testforge.testjob.service.TestJobService;
import io.testforge.runorchestrator.comparison.model.CreateComparisonRunCommand;
import io.testforge.runorchestrator.comparison.model.ComparisonRunView;
import io.testforge.runorchestrator.comparison.service.ComparisonRunService;
import io.testforge.runorchestrator.run.service.RunIdempotencyConflictException;
import io.testforge.runorchestrator.run.service.RunStateConflictException;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import io.testforge.testjob.model.TestJobSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class TestJobLaunchService {
    private final ObjectProvider<TestJobService> jobs;
    private final RunTaskService runs;
    private final ObjectMapper objectMapper;
    private final ComparisonRunService comparisons;
    private final ProjectCatalogService projects;
    private final ConcurrentMap<UUID, Object> executionLocks = new ConcurrentHashMap<>();

    public TestJobLaunchService(ObjectProvider<TestJobService> jobs, RunTaskService runs, ObjectMapper objectMapper,
                                ComparisonRunService comparisons, ProjectCatalogService projects) {
        this.jobs = jobs; this.runs = runs; this.objectMapper = objectMapper;
        this.comparisons = comparisons; this.projects = projects;
    }

    public RunView launch(UUID testJobId, UUID requestKey) {
        var existing = runs.findByRequestKey(requestKey);
        if (existing.isPresent()) {
            if (!testJobId.equals(existing.get().testJobId())) {
                throw new RunIdempotencyConflictException("requestKey 已用于其他 Test Job");
            }
            return existing.get();
        }
        synchronized (executionLocks.computeIfAbsent(testJobId, ignored -> new Object())) {
            var rechecked = runs.findByRequestKey(requestKey);
            if (rechecked.isPresent()) {
                if (!testJobId.equals(rechecked.get().testJobId())) {
                    throw new RunIdempotencyConflictException("requestKey 已用于其他 Test Job");
                }
                return rechecked.get();
            }
            if (runs.listRunsByTestJob(testJobId).stream().anyMatch(run -> !run.state().isTerminal())) {
                throw new RunStateConflictException("测试任务已有活动执行 Attempt，请等待结束后重新执行");
            }
            var prepared = jobs().prepareLaunch(testJobId);
            var job = prepared.job();
            UUID profileId = prepared.deploymentProfileId();
            String snapshotJson;
            try {
                snapshotJson = objectMapper.writeValueAsString(prepared.snapshot());
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("TestJobSnapshot 无法序列化", error);
            }
            try {
                return runs.createRun(new CreateRunCommand(job.projectId(), prepared.workflow().targetId(), null,
                        job.workflowId(), job.workflowVersion(), job.priority(),
                        Math.min(20, Math.max(job.processConcurrency(), job.deviceConcurrency())),
                        job.processConcurrency(), job.deviceConcurrency(), requestKey,
                        new RevisionSelector(RevisionType.COMMIT, prepared.revision().commitSha()), Map.of(), profileId,
                        job.id(), job.configVersion(), snapshotJson, prepared.providerEnvironmentKey(), job.platform().name()));
            } catch (RunIdempotencyConflictException conflict) {
                return runs.findByRequestKey(requestKey)
                        .filter(run -> testJobId.equals(run.testJobId()))
                        .orElseThrow(() -> conflict);
            }
        }
    }

    public ComparisonRunView launchComparison(UUID testJobId, UUID requestKey,
                                              RevisionSelector baselineSelector,
                                              RevisionSelector candidateSelector) {
        var existing = comparisons.findByRequestKey(requestKey);
        if (existing.isPresent()) {
            if (!testJobId.equals(existing.get().testJobId())) {
                throw new RunIdempotencyConflictException("requestKey 已用于其他 Test Job 对比");
            }
            return existing.get();
        }
        var prepared = jobs().prepareLaunch(testJobId);
        var job = prepared.job();
        var baseline = projects.resolveProjectRevision(job.projectId(), baselineSelector);
        var candidate = projects.resolveProjectRevision(job.projectId(), candidateSelector);
        UUID profileId = prepared.deploymentProfileId();
        try {
            return comparisons.create(new CreateComparisonRunCommand(job.projectId(), prepared.workflow().targetId(),
                    null, job.workflowId(), job.workflowVersion(), job.priority(),
                    Math.min(20, Math.max(job.processConcurrency(), job.deviceConcurrency())),
                    job.processConcurrency(), job.deviceConcurrency(), requestKey,
                    new RevisionSelector(RevisionType.COMMIT, baseline.commitSha()),
                    new RevisionSelector(RevisionType.COMMIT, candidate.commitSha()), profileId,
                    job.id(), job.configVersion(), json(withCommit(prepared.snapshot(), baseline.commitSha(),
                    baseline.requestedType(), baseline.requestedValue())), json(withCommit(prepared.snapshot(),
                    candidate.commitSha(), candidate.requestedType(), candidate.requestedValue())),
                    prepared.providerEnvironmentKey(), job.platform().name()));
        } catch (RunIdempotencyConflictException conflict) {
            return comparisons.findByRequestKey(requestKey)
                    .filter(run -> testJobId.equals(run.testJobId()))
                    .orElseThrow(() -> conflict);
        }
    }

    private TestJobSnapshot withCommit(TestJobSnapshot source, String commit, RevisionType type, String value) {
        return new TestJobSnapshot(source.testJobId(), source.testJobName(), source.configVersion(),
                source.projectId(), source.projectName(), source.repositoryUrl(), source.workflowId(),
                source.workflowVersion(), source.workflowChecksum(), type, value, commit, source.pipelineId(),
                source.pipelineProvider(), source.pipelineName(), source.pipelineRevision(), source.environmentExternalId(),
                source.environmentName(), source.requestedPlatform(), source.environmentResourcePoolKey(),
                source.priority(), source.processConcurrency(),
                source.deviceConcurrency(), source.capturedAt());
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("TestJobSnapshot 无法序列化", error); }
    }

    private TestJobService jobs() {
        return jobs.getIfAvailable(() -> {
            throw new IllegalStateException("Test Job 模块未启用");
        });
    }
}
