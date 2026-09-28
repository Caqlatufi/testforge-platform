package io.testforge.casecatalog.testcase.model;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record CaseDefinition(
        String yaml,
        String digest,
        String name,
        TestCaseKind kind,
        Set<String> tags,
        Map<String, Object> parameters,
        int timeoutSeconds,
        ExecutionRequirement executionRequirement,
        ScriptDefinition script
) {
    public record ScriptDefinition(
            String type,
            UUID assetId,
            String entrypoint,
            String sourceRef,
            String checksum,
            String inlineContent
    ) {
    }
}
