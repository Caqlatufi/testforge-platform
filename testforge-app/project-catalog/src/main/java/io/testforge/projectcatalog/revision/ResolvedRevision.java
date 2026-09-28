package io.testforge.projectcatalog.revision;

import java.time.Instant;

public record ResolvedRevision(
        RevisionType requestedType,
        String requestedValue,
        String commitSha,
        Instant resolvedAt
) {
}
