package io.testforge.report.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "report_test_result",
        indexes = {
                @Index(name = "idx_report_case_history", columnList = "case_id,recorded_at"),
                @Index(name = "idx_report_run", columnList = "run_id")
        }
)
public class TestResultEntity {
    @Id
    private UUID taskId;
    @Column(nullable = false)
    private UUID runId;
    @Column(nullable = false)
    private UUID caseId;
    @Column(nullable = false, unique = true)
    private UUID attemptId;
    @Column(nullable = false, length = 32)
    private String status;
    @Column(nullable = false)
    private long durationMs;
    @Column(length = 64)
    private String failureType;
    @Column(nullable = false, length = 2000)
    private String summary;
    @Column(nullable = false, columnDefinition = "text")
    private String artifactKeysJson;
    @Column(nullable = false)
    private Instant recordedAt;

    protected TestResultEntity() { }

    public TestResultEntity(UUID taskId, UUID runId, UUID caseId, UUID attemptId, String status, long durationMs,
                            String failureType, String summary, String artifactKeysJson, Instant recordedAt) {
        this.taskId = taskId;
        this.runId = runId;
        this.caseId = caseId;
        this.attemptId = attemptId;
        this.status = status;
        this.durationMs = durationMs;
        this.failureType = failureType;
        this.summary = summary;
        this.artifactKeysJson = artifactKeysJson;
        this.recordedAt = recordedAt;
    }

    public UUID getTaskId() { return taskId; }
    public UUID getRunId() { return runId; }
    public UUID getCaseId() { return caseId; }
    public UUID getAttemptId() { return attemptId; }
    public String getStatus() { return status; }
    public long getDurationMs() { return durationMs; }
    public String getFailureType() { return failureType; }
    public String getSummary() { return summary; }
    public String getArtifactKeysJson() { return artifactKeysJson; }
    public Instant getRecordedAt() { return recordedAt; }

    public void replace(UUID runId, UUID caseId, UUID attemptId, String status, long durationMs, String failureType,
                        String summary, String artifactKeysJson, Instant recordedAt) {
        this.runId = runId;
        this.caseId = caseId;
        this.attemptId = attemptId;
        this.status = status;
        this.durationMs = durationMs;
        this.failureType = failureType;
        this.summary = summary;
        this.artifactKeysJson = artifactKeysJson;
        this.recordedAt = recordedAt;
    }
}
