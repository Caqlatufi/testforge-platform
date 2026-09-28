package io.testforge.projectcatalog.revision;

import java.time.Instant;

public record RevisionOption(
        RevisionType type,
        String name,
        String commitSha,
        String commitMessage,
        Instant committedAt,
        boolean defaultBranch
) {
}
