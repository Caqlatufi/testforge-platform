package io.testforge.aidiagnosis.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_diagnosis_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_ai_diagnosis_request", columnNames = {"report_id", "request_key"}),
        indexes = @Index(name = "idx_ai_diagnosis_report_completed", columnList = "report_id,completed_at"))
public class AiDiagnosisRecordEntity {
    @Id private UUID id;
    @Column(name = "report_id", nullable = false) private UUID reportId;
    @Column(name = "request_key", nullable = false) private UUID requestKey;
    @Column(name = "evidence_hash", nullable = false, length = 64) private String evidenceHash;
    @Column(nullable = false, length = 16) private String status;
    @Column(length = 32) private String category;
    private Double confidence;
    @Column(name = "evidence_json", columnDefinition = "text") private String evidenceJson;
    @Column(name = "suggestions_json", columnDefinition = "text") private String suggestionsJson;
    @Column(name = "missing_evidence_json", columnDefinition = "text") private String missingEvidenceJson;
    @Column(nullable = false, length = 32) private String provider;
    @Column(nullable = false, length = 64) private String model;
    @Column(name = "reasoning_effort", nullable = false, length = 16) private String reasoningEffort;
    @Column(name = "error_code", length = 64) private String errorCode;
    @Column(name = "error_message", length = 2000) private String errorMessage;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "duration_ms") private Long durationMs;
    @Version private long version;

    protected AiDiagnosisRecordEntity() { }

    public static AiDiagnosisRecordEntity running(UUID reportId, UUID requestKey, String evidenceHash,
                                                   String provider, String model, String effort, Instant now) {
        AiDiagnosisRecordEntity value = new AiDiagnosisRecordEntity();
        value.id = UUID.randomUUID(); value.reportId = reportId; value.requestKey = requestKey;
        value.evidenceHash = evidenceHash; value.status = "RUNNING"; value.provider = provider;
        value.model = model; value.reasoningEffort = effort; value.startedAt = now;
        return value;
    }

    public void succeed(String category, double confidence, String evidenceJson, String suggestionsJson,
                        String missingEvidenceJson, Instant completedAt) {
        this.status = "SUCCEEDED"; this.category = category; this.confidence = confidence;
        this.evidenceJson = evidenceJson; this.suggestionsJson = suggestionsJson;
        this.missingEvidenceJson = missingEvidenceJson; finish(completedAt);
    }

    public void fail(String code, String message, Instant completedAt) {
        this.status = "FAILED"; this.errorCode = code;
        this.errorMessage = message == null ? null : message.substring(0, Math.min(2000, message.length()));
        finish(completedAt);
    }

    private void finish(Instant completedAt) {
        this.completedAt = completedAt; this.durationMs = Math.max(0, completedAt.toEpochMilli() - startedAt.toEpochMilli());
    }

    public UUID getId() { return id; }
    public UUID getReportId() { return reportId; }
    public UUID getRequestKey() { return requestKey; }
    public String getEvidenceHash() { return evidenceHash; }
    public String getStatus() { return status; }
    public String getCategory() { return category; }
    public Double getConfidence() { return confidence; }
    public String getEvidenceJson() { return evidenceJson; }
    public String getSuggestionsJson() { return suggestionsJson; }
    public String getMissingEvidenceJson() { return missingEvidenceJson; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getReasoningEffort() { return reasoningEffort; }
    public Instant getCompletedAt() { return completedAt; }
}
