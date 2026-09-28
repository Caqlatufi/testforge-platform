package io.testforge.report.service;

import io.testforge.runorchestrator.task.model.TaskTargetRevision;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RunReport(
        UUID runId,
        String runState,
        int total,
        int passed,
        int failed,
        int pending,
        long durationMs,
        long p50DurationMs,
        long p95DurationMs,
        Map<String, Long> failureCategories,
        List<ResultItem> results,
        List<String> artifactKeys
) {
    public record ResultItem(
            UUID taskId, UUID caseId, UUID attemptId, String status, long durationMs,
            String failureType, String summary, List<String> artifactKeys,
            TaskTargetRevision targetRevision
    ) { }
}
