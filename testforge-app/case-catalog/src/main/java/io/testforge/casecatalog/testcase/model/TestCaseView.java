package io.testforge.casecatalog.testcase.model;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record TestCaseView(
        UUID id,
        UUID projectId,
        UUID targetId,
        String name,
        TestCaseKind kind,
        CaseScope scope,
        Set<String> tags,
        Map<String, Object> parameters,
        int timeoutSeconds,
        UUID scriptVersionId,
        Instant createdAt,
        Instant updatedAt,
        ExecutionRequirement executionRequirement,
        boolean yamlManaged,
        String definitionDigest
) {
    public TestCaseView(UUID id, UUID projectId, UUID targetId, String name, TestCaseKind kind,
                        Set<String> tags, Map<String, Object> parameters, int timeoutSeconds,
                        UUID scriptVersionId, Instant createdAt, Instant updatedAt) {
        this(id, projectId, targetId, name, kind, CaseScope.PROJECT, tags, parameters,
                timeoutSeconds, scriptVersionId, createdAt, updatedAt,
                ExecutionRequirement.legacyDefault((String) null), false, null);
    }

    public TestCaseView(UUID id, UUID projectId, UUID targetId, String name, TestCaseKind kind,
                        CaseScope scope, Set<String> tags, Map<String, Object> parameters,
                        int timeoutSeconds, UUID scriptVersionId, Instant createdAt, Instant updatedAt) {
        this(id, projectId, targetId, name, kind, scope, tags, parameters, timeoutSeconds,
                scriptVersionId, createdAt, updatedAt, ExecutionRequirement.legacyDefault((String) null), false, null);
    }

    public TestCaseView(UUID id, UUID projectId, UUID targetId, String name, TestCaseKind kind,
                        CaseScope scope, Set<String> tags, Map<String, Object> parameters,
                        int timeoutSeconds, UUID scriptVersionId, Instant createdAt, Instant updatedAt,
                        ExecutionRequirement executionRequirement) {
        this(id, projectId, targetId, name, kind, scope, tags, parameters, timeoutSeconds,
                scriptVersionId, createdAt, updatedAt, executionRequirement, false, null);
    }
}
