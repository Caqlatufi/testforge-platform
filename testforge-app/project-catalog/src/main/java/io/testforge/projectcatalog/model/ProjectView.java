package io.testforge.projectcatalog.model;

import java.util.List;
import java.util.UUID;

public record ProjectView(
        UUID id,
        String name,
        String code,
        ProjectState state,
        TargetType targetType,
        String repositoryUrl,
        String defaultBranch,
        String ciProvider,
        String ciServerUrl,
        String ciFolder,
        String ciCredentialRef,
        List<TargetView> targets
) {
    public ProjectView(UUID id, String name, String code, ProjectState state, List<TargetView> targets) {
        this(id, name, code, state, null, null, null, null, null, null, null, targets);
    }
}
