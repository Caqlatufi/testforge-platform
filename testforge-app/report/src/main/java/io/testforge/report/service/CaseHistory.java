package io.testforge.report.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CaseHistory(
        UUID caseId,
        int sampleSize,
        boolean sufficientData,
        boolean flaky,
        int statusTransitions,
        String latestFailureType,
        List<HistoryItem> recentResults
) {
    public CaseHistory {
        recentResults = List.copyOf(recentResults);
    }

    public record HistoryItem(
            Instant recordedAt,
            UUID runId,
            UUID taskId,
            UUID attemptId,
            String finalStatus,
            long durationMs,
            String failureType
    ) { }
}
