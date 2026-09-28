package io.testforge.report.model;

import java.util.Objects;

/**
 * 可被诊断建议引用的只读报告证据。
 */
public record EvidenceReference(String evidenceId, EvidenceType type, String summary) {

    public EvidenceReference {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(summary, "summary");
    }
}
