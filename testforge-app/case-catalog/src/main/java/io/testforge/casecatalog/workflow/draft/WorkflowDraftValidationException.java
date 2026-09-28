package io.testforge.casecatalog.workflow.draft;

import java.util.List;
import java.util.Objects;

/**
 * 草稿图不满足领域约束。
 */
public final class WorkflowDraftValidationException extends IllegalArgumentException {

    private final List<WorkflowDraftViolation> violations;

    public WorkflowDraftValidationException(List<WorkflowDraftViolation> violations) {
        super(firstMessage(violations));
        this.violations = List.copyOf(violations);
    }

    public List<WorkflowDraftViolation> violations() {
        return violations;
    }

    public boolean hasViolation(WorkflowDraftViolationCode code) {
        Objects.requireNonNull(code, "code 不能为空");
        return violations.stream().anyMatch(violation -> violation.code() == code);
    }

    private static String firstMessage(List<WorkflowDraftViolation> violations) {
        Objects.requireNonNull(violations, "violations 不能为空");
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("violations 不能为空");
        }
        return violations.getFirst().message();
    }
}
