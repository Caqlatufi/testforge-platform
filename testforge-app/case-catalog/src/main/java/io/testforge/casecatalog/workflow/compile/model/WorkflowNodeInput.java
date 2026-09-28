package io.testforge.casecatalog.workflow.compile.model;

import java.util.Map;
import java.util.UUID;

public record WorkflowNodeInput(
        UUID id,
        PublishNodeType type,
        UUID referenceId,
        int referenceVersion,
        boolean required,
        Integer timeoutSeconds,
        Map<String, Object> parameterOverrides
) {

    public WorkflowNodeInput {
        parameterOverrides = parameterOverrides == null ? Map.of() : Map.copyOf(parameterOverrides);
    }
}
