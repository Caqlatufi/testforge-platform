package io.testforge.casecatalog.suite.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record UpdateTestSuiteCommand(
        long expectedVersion,
        String name,
        List<UUID> caseIds,
        List<String> tags,
        Map<String, Object> parameterBindings
) {
}
