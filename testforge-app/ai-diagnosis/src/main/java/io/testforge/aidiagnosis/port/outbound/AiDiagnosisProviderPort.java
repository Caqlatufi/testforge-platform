package io.testforge.aidiagnosis.port.outbound;

import io.testforge.aidiagnosis.model.ProviderDiagnosis;
import io.testforge.aidiagnosis.model.ProviderStatus;
import io.testforge.report.model.ReportEvidence;

/**
 * 外部模型 Provider 的可替换出站端口。
 */
public interface AiDiagnosisProviderPort {

    ProviderDiagnosis diagnose(ReportEvidence evidence);

    ProviderStatus status();
}
