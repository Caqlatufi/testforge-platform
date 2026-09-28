package io.testforge.casecatalog.testcase.model;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record CaseDefinitionView(
        UUID caseId,
        UUID projectId,
        boolean valid,
        String yaml,
        String digest,
        String name,
        TestCaseKind kind,
        Set<String> tags,
        Map<String, Object> parameters,
        int timeoutSeconds,
        ExecutionRequirement executionRequirement,
        UUID assetId,
        String sourceRef,
        String entrypoint,
        String checksum
) {
}
