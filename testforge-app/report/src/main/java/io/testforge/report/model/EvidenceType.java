package io.testforge.report.model;

/**
 * 报告能够提供给诊断模块的证据来源类型。
 */
public enum EvidenceType {
    ASSERTION,
    LOG,
    ENVIRONMENT,
    TRACE,
    HISTORY
}
