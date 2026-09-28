package io.testforge.casecatalog.workflow.draft;

import java.util.Objects;

/**
 * 一项可展示给编辑器用户的草稿图校验错误。
 */
public record WorkflowDraftViolation(
        WorkflowDraftViolationCode code,
        String message
) {

    public WorkflowDraftViolation {
        Objects.requireNonNull(code, "code 不能为空");
        Objects.requireNonNull(message, "message 不能为空");
    }
}
