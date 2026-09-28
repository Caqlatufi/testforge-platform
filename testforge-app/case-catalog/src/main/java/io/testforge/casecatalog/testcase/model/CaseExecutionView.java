package io.testforge.casecatalog.testcase.model;

import java.util.UUID;

/**
 * Workflow 发布校验或 Run 创建执行快照时使用的 Case 执行视图。
 */
public record CaseExecutionView(
        UUID caseId,
        String name,
        UUID projectId,
        UUID targetId,
        TestCaseKind kind,
        CaseScope scope,
        int timeoutSeconds,
        UUID scriptVersionId,
        int scriptVersion,
        ScriptRunner runner,
        String sourceRef,
        String checksum,
        ExecutionRequirement executionRequirement
) {
    public CaseExecutionView(UUID caseId, UUID projectId, UUID targetId, TestCaseKind kind,
                             CaseScope scope, int timeoutSeconds, UUID scriptVersionId,
                             int scriptVersion, ScriptRunner runner, String sourceRef, String checksum) {
        this(caseId, null, projectId, targetId, kind, scope, timeoutSeconds, scriptVersionId,
                scriptVersion, runner, sourceRef, checksum, ExecutionRequirement.legacyDefault(runner));
    }
}
