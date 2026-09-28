package io.testforge.report.service;

import io.testforge.report.persistence.TestResultEntity;
import io.testforge.report.persistence.TestResultRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public class CaseHistoryService {
    private final TestResultRepository repository;

    public CaseHistoryService(TestResultRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public CaseHistory get(UUID caseId) {
        if (caseId == null) {
            throw new IllegalArgumentException("caseId 不能为空");
        }
        List<TestResultEntity> entities = repository.findTop10ByCaseIdOrderByRecordedAtDesc(caseId);
        List<CaseHistory.HistoryItem> items = entities.stream().map(this::item).toList();
        boolean hasPassed = items.stream().anyMatch(item -> "PASSED".equals(item.finalStatus()));
        boolean hasNonPassed = items.stream().anyMatch(item -> !"PASSED".equals(item.finalStatus()));
        int transitions = 0;
        for (int index = 1; index < items.size(); index++) {
            if (!items.get(index - 1).finalStatus().equals(items.get(index).finalStatus())) {
                transitions++;
            }
        }
        String latestFailure = items.stream()
                .filter(item -> !"PASSED".equals(item.finalStatus()))
                .map(CaseHistory.HistoryItem::failureType)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
        boolean sufficient = items.size() >= 2;
        return new CaseHistory(
                caseId,
                items.size(),
                sufficient,
                sufficient && hasPassed && hasNonPassed,
                transitions,
                latestFailure,
                items
        );
    }

    private CaseHistory.HistoryItem item(TestResultEntity entity) {
        return new CaseHistory.HistoryItem(
                entity.getRecordedAt(), entity.getRunId(), entity.getTaskId(), entity.getAttemptId(),
                entity.getStatus(), entity.getDurationMs(), entity.getFailureType()
        );
    }
}
