package io.testforge.report.service;

import io.testforge.report.persistence.TestResultEntity;
import io.testforge.report.persistence.TestResultRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaseHistoryServiceTest {
    private static final UUID CASE_ID = UUID.randomUUID();
    private final TestResultRepository repository = mock(TestResultRepository.class);
    private final CaseHistoryService service = new CaseHistoryService(repository);

    @Test
    void reportsStablePassingHistory() {
        when(repository.findTop10ByCaseIdOrderByRecordedAtDesc(CASE_ID))
                .thenReturn(List.of(result(0, "PASSED", null), result(1, "PASSED", null)));

        CaseHistory history = service.get(CASE_ID);

        assertThat(history.sufficientData()).isTrue();
        assertThat(history.flaky()).isFalse();
        assertThat(history.statusTransitions()).isZero();
    }

    @Test
    void reportsStableFailureAndLatestFailureCategory() {
        when(repository.findTop10ByCaseIdOrderByRecordedAtDesc(CASE_ID)).thenReturn(List.of(
                result(0, "ASSERTION_FAILED", "PRODUCT_DEFECT"),
                result(1, "ASSERTION_FAILED", "PRODUCT_DEFECT")
        ));

        CaseHistory history = service.get(CASE_ID);

        assertThat(history.flaky()).isFalse();
        assertThat(history.latestFailureType()).isEqualTo("PRODUCT_DEFECT");
    }

    @Test
    void reportsExplainableFlakyEvidence() {
        when(repository.findTop10ByCaseIdOrderByRecordedAtDesc(CASE_ID)).thenReturn(List.of(
                result(0, "PASSED", null),
                result(1, "ASSERTION_FAILED", "PRODUCT_DEFECT"),
                result(2, "PASSED", null)
        ));

        CaseHistory history = service.get(CASE_ID);

        assertThat(history.flaky()).isTrue();
        assertThat(history.statusTransitions()).isEqualTo(2);
        assertThat(history.latestFailureType()).isEqualTo("PRODUCT_DEFECT");
        assertThat(history.recentResults()).extracting(CaseHistory.HistoryItem::finalStatus)
                .containsExactly("PASSED", "ASSERTION_FAILED", "PASSED");
        assertThat(history.recentResults()).extracting(CaseHistory.HistoryItem::runId)
                .doesNotContainNull();
    }

    @Test
    void doesNotClassifyInsufficientDataAsFlaky() {
        when(repository.findTop10ByCaseIdOrderByRecordedAtDesc(CASE_ID))
                .thenReturn(List.of(result(0, "PASSED", null)));

        CaseHistory history = service.get(CASE_ID);

        assertThat(history.sufficientData()).isFalse();
        assertThat(history.flaky()).isFalse();
    }

    @Test
    void usesOneBoundedRepositoryQuery() {
        when(repository.findTop10ByCaseIdOrderByRecordedAtDesc(CASE_ID)).thenReturn(List.of());

        service.get(CASE_ID);

        verify(repository).findTop10ByCaseIdOrderByRecordedAtDesc(CASE_ID);
    }

    private TestResultEntity result(int offset, String status, String failureType) {
        return new TestResultEntity(
                UUID.randomUUID(), UUID.randomUUID(), CASE_ID, UUID.randomUUID(), status, 100 + offset,
                failureType, status, "[]", Instant.parse("2026-09-20T00:00:00Z").minusSeconds(offset)
        );
    }
}
