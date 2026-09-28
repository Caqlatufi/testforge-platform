package io.testforge.casecatalog.testcase.model;

import java.time.Instant;
import java.util.UUID;

public record ScriptVersionView(
        UUID id,
        UUID caseId,
        ScriptRunner runner,
        String sourceRef,
        String checksum,
        int version,
        Instant createdAt
) {
}
