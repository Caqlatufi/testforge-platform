package io.testforge.casecatalog.suite.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TestSuiteView(
        UUID id,
        UUID projectId,
        UUID targetId,
        String name,
        List<UUID> caseIds,
        List<String> tags,
        Map<String, Object> parameterBindings,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
