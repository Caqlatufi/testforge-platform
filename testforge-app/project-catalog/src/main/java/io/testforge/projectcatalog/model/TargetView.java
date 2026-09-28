package io.testforge.projectcatalog.model;

import java.util.List;
import java.util.UUID;

public record TargetView(
        UUID id,
        UUID projectId,
        String name,
        TargetType type,
        String repositoryUrl,
        String defaultBranch,
        List<EnvironmentView> environments
) {
    public TargetView(
            UUID id,
            UUID projectId,
            String name,
            TargetType type,
            List<EnvironmentView> environments
    ) {
        this(id, projectId, name, type, null, null, environments);
    }
}
