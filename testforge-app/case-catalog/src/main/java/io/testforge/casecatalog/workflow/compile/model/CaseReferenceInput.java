package io.testforge.casecatalog.workflow.compile.model;

import io.testforge.casecatalog.testcase.model.ExecutionRequirement;

import java.util.Map;
import java.util.UUID;

public record CaseReferenceInput(
        UUID caseId,
        String caseName,
        int scriptVersion,
        UUID projectId,
        UUID targetId,
        ExecutableNodeType kind,
        UUID scriptVersionId,
        String runner,
        String sourceRef,
        String checksum,
        int timeoutSeconds,
        Map<String, Object> parameters,
        boolean shared,
        ExecutionRequirement executionRequirement
) {

    public CaseReferenceInput {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        executionRequirement = executionRequirement == null
                ? ExecutionRequirement.legacyDefault(runner)
                : executionRequirement;
    }

    public ReferenceKey key() {
        return new ReferenceKey(caseId, scriptVersion);
    }

    public CaseReferenceInput(UUID caseId, int scriptVersion, UUID projectId, UUID targetId,
                              ExecutableNodeType kind, UUID scriptVersionId, String runner,
                              String sourceRef, String checksum, int timeoutSeconds,
                              Map<String, Object> parameters) {
        this(caseId, null, scriptVersion, projectId, targetId, kind, scriptVersionId, runner,
                sourceRef, checksum, timeoutSeconds, parameters, false,
                ExecutionRequirement.legacyDefault(runner));
    }

    public CaseReferenceInput(UUID caseId, int scriptVersion, UUID projectId, UUID targetId,
                              ExecutableNodeType kind, UUID scriptVersionId, String runner,
                              String sourceRef, String checksum, int timeoutSeconds,
                              Map<String, Object> parameters, boolean shared) {
        this(caseId, null, scriptVersion, projectId, targetId, kind, scriptVersionId, runner,
                sourceRef, checksum, timeoutSeconds, parameters, shared,
                ExecutionRequirement.legacyDefault(runner));
    }

    public CaseReferenceInput(UUID caseId, int scriptVersion, UUID projectId, UUID targetId,
                              ExecutableNodeType kind, UUID scriptVersionId, String runner,
                              String sourceRef, String checksum, int timeoutSeconds,
                              Map<String, Object> parameters, boolean shared,
                              ExecutionRequirement executionRequirement) {
        this(caseId, null, scriptVersion, projectId, targetId, kind, scriptVersionId, runner,
                sourceRef, checksum, timeoutSeconds, parameters, shared, executionRequirement);
    }
}
