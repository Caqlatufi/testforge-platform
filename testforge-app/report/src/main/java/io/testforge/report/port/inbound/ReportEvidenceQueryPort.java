package io.testforge.report.port.inbound;

import io.testforge.report.model.ReportEvidence;

import java.util.Optional;
import java.util.UUID;

/**
 * 向其他模块提供报告证据的只读端口。
 */
public interface ReportEvidenceQueryPort {

    Optional<ReportEvidence> findByReportId(UUID reportId);
}
