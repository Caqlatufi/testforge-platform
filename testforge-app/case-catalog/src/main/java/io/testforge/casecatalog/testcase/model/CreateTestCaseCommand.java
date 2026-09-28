package io.testforge.casecatalog.testcase.model;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record CreateTestCaseCommand(
        UUID targetId,
        String name,
        TestCaseKind kind,
        CaseScope scope,
        Map<String, Object> parameterSchema,
        Set<String> tags,
        Integer timeoutSeconds,
        ExecutionRequirement executionRequirement
) {
    public CreateTestCaseCommand(UUID targetId, String name, TestCaseKind kind, CaseScope scope,
                                 Map<String, Object> parameterSchema, Set<String> tags,
                                 Integer timeoutSeconds) {
        this(targetId, name, kind, scope, parameterSchema, tags, timeoutSeconds, null);
    }

    public CreateTestCaseCommand(UUID targetId, String name, TestCaseKind kind,
                                 Map<String, Object> parameterSchema, Set<String> tags,
                                 Integer timeoutSeconds) {
        this(targetId, name, kind, CaseScope.PROJECT, parameterSchema, tags, timeoutSeconds, null);
    }
}
