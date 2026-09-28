package io.testforge.casecatalog.workflow.compile.model;

import io.testforge.casecatalog.testcase.model.ExecutionRequirement;

import java.util.Map;
import java.util.UUID;

public record CompiledNode(
        UUID id,
        String sourcePath,
        PublishNodeType sourceType,
        ExecutableNodeType type,
        UUID caseId,
        String displayName,
        UUID scriptVersionId,
        int scriptVersion,
        boolean required,
        String runner,
        String sourceRef,
        String scriptChecksum,
        int timeoutSeconds,
        Map<String, Object> parameters,
        ExecutionRequirement executionRequirement
) {

    public CompiledNode {
        parameters = Map.copyOf(parameters);
        executionRequirement = executionRequirement == null
                ? ExecutionRequirement.legacyDefault(runner)
                : executionRequirement;
    }

    public CompiledNode(UUID id, String sourcePath, PublishNodeType sourceType,
                        ExecutableNodeType type, UUID caseId, UUID scriptVersionId,
                        int scriptVersion, boolean required, String runner, String sourceRef,
                        String scriptChecksum, int timeoutSeconds, Map<String, Object> parameters) {
        this(id, sourcePath, sourceType, type, caseId, null, scriptVersionId, scriptVersion, required,
                runner, sourceRef, scriptChecksum, timeoutSeconds, parameters,
                ExecutionRequirement.legacyDefault(runner));
    }

    public CompiledNode(UUID id, String sourcePath, PublishNodeType sourceType,
                        ExecutableNodeType type, UUID caseId, UUID scriptVersionId,
                        int scriptVersion, boolean required, String runner, String sourceRef,
                        String scriptChecksum, int timeoutSeconds, Map<String, Object> parameters,
                        ExecutionRequirement executionRequirement) {
        this(id, sourcePath, sourceType, type, caseId, null, scriptVersionId, scriptVersion, required,
                runner, sourceRef, scriptChecksum, timeoutSeconds, parameters, executionRequirement);
    }
}
