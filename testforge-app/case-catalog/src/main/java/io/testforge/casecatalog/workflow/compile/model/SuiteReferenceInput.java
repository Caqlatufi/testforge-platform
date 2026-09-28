package io.testforge.casecatalog.workflow.compile.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SuiteReferenceInput(
        UUID suiteId,
        int version,
        UUID projectId,
        UUID targetId,
        List<CaseReferenceInput> members,
        Map<String, Object> parameterBindings
) {

    public SuiteReferenceInput {
        members = members == null ? List.of() : List.copyOf(members);
        parameterBindings = parameterBindings == null ? Map.of() : Map.copyOf(parameterBindings);
    }

    public ReferenceKey key() {
        return new ReferenceKey(suiteId, version);
    }
}
