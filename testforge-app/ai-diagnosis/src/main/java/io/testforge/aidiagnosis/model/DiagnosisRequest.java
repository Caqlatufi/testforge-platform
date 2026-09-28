package io.testforge.aidiagnosis.model;

import java.util.Objects;
import java.util.UUID;

/**
 * 请求生成报告诊断建议的幂等命令。
 */
public record DiagnosisRequest(UUID reportId, UUID requestKey, boolean forceRefresh) {

    public DiagnosisRequest {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(requestKey, "requestKey");
    }
}
