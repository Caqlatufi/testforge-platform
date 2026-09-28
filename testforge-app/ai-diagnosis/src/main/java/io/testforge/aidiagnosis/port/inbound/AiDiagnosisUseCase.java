package io.testforge.aidiagnosis.port.inbound;

import io.testforge.aidiagnosis.model.DiagnosisAdvice;
import io.testforge.aidiagnosis.model.DiagnosisRequest;

import java.util.Optional;
import java.util.UUID;

/**
 * 生成只读诊断建议的 AI 模块入站端口。
 */
public interface AiDiagnosisUseCase {

    DiagnosisAdvice diagnose(DiagnosisRequest request);

    Optional<DiagnosisAdvice> latest(UUID reportId);
}
