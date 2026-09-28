package io.testforge.report.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 报告模块向 AI 诊断模块暴露的只读证据快照。
 */
public record ReportEvidence(UUID reportId, List<EvidenceReference> evidence) {

    public ReportEvidence {
        Objects.requireNonNull(reportId, "reportId");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }
}
