package io.testforge.aidiagnosis.model;

import java.util.List;
import java.util.Objects;
import java.time.Instant;
import java.util.UUID;

/**
 * 带证据引用的 AI 建议；该类型不表示确定性测试结论。
 */
public record DiagnosisAdvice(
        UUID diagnosisId,
        UUID reportId,
        String category,
        double confidence,
        List<EvidenceCitation> evidence,
        List<String> suggestions,
        List<String> missingEvidence,
        String provider,
        String model,
        String reasoningEffort,
        boolean reused,
        Instant generatedAt) {

    public DiagnosisAdvice {
        Objects.requireNonNull(diagnosisId, "diagnosisId");
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(category, "category");
        if (confidence < 0 || confidence > 1) throw new IllegalArgumentException("confidence must be between 0 and 1");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        suggestions = List.copyOf(Objects.requireNonNull(suggestions, "suggestions"));
        missingEvidence = List.copyOf(Objects.requireNonNull(missingEvidence, "missingEvidence"));
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(reasoningEffort, "reasoningEffort");
        Objects.requireNonNull(generatedAt, "generatedAt");
    }
}
