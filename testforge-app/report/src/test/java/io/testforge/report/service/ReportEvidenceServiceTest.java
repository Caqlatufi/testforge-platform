package io.testforge.report.service;

import io.testforge.report.persistence.TestResultEntity;
import io.testforge.report.persistence.TestResultRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportEvidenceServiceTest {
    @Test
    void exposesOnlyBoundedReadOnlyResultAndArtifactEvidence() {
        TestResultRepository repository = mock(TestResultRepository.class);
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(repository.findAllByRunIdOrderByRecordedAtAsc(runId)).thenReturn(List.of(new TestResultEntity(
                taskId, runId, UUID.randomUUID(), UUID.randomUUID(), "ASSERTION_FAILED", 50,
                "SCRIPT_ERROR", "locator missing", "[\"runs/demo/log.txt\"]", Instant.now())));

        var evidence = new ReportEvidenceService(repository).findByReportId(runId).orElseThrow();

        assertEquals(2, evidence.evidence().size());
        assertEquals("result:" + taskId, evidence.evidence().getFirst().evidenceId());
        assertTrue(evidence.evidence().get(1).summary().contains("log.txt"));
    }
}
