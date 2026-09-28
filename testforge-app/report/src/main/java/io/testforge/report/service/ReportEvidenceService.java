package io.testforge.report.service;

import io.testforge.report.model.EvidenceReference;
import io.testforge.report.model.EvidenceType;
import io.testforge.report.model.ReportEvidence;
import io.testforge.report.persistence.TestResultEntity;
import io.testforge.report.persistence.TestResultRepository;
import io.testforge.report.port.inbound.ReportEvidenceQueryPort;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Builds the bounded, read-only evidence snapshot consumed by AI diagnosis. */
public class ReportEvidenceService implements ReportEvidenceQueryPort {
    private static final int MAX_SUMMARY_CHARS = 2_000;
    private final TestResultRepository repository;

    public ReportEvidenceService(TestResultRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReportEvidence> findByReportId(UUID reportId) {
        List<TestResultEntity> results = repository.findAllByRunIdOrderByRecordedAtAsc(reportId);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        List<EvidenceReference> evidence = new ArrayList<>();
        for (TestResultEntity result : results) {
            String summary = "status=" + result.getStatus()
                    + ", failureType=" + nullSafe(result.getFailureType())
                    + ", durationMs=" + result.getDurationMs()
                    + ", summary=" + result.getSummary();
            evidence.add(new EvidenceReference(
                    "result:" + result.getTaskId(),
                    EvidenceType.ASSERTION,
                    truncate(summary)
            ));
            if (result.getArtifactKeysJson() != null && !"[]".equals(result.getArtifactKeysJson())) {
                evidence.add(new EvidenceReference(
                        "artifacts:" + result.getTaskId(),
                        EvidenceType.LOG,
                        truncate("artifact references=" + result.getArtifactKeysJson())
                ));
            }
        }
        return Optional.of(new ReportEvidence(reportId, evidence));
    }

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "NONE" : value;
    }

    private String truncate(String value) {
        return value.length() <= MAX_SUMMARY_CHARS ? value : value.substring(0, MAX_SUMMARY_CHARS);
    }
}
