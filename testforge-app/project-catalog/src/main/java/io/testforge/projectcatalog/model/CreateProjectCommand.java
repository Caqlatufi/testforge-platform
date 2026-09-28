package io.testforge.projectcatalog.model;

public record CreateProjectCommand(
        String name,
        String code,
        TargetType targetType,
        String repositoryUrl,
        String defaultBranch,
        String ciProvider,
        String ciServerUrl,
        String ciFolder,
        String ciCredentialRef
) {
    public CreateProjectCommand(String name, String code) {
        this(name, code, null, null, null, null, null, null, null);
    }
}
