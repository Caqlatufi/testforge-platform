package io.testforge.projectcatalog.model;

public record CreateTargetCommand(
        String name,
        TargetType type,
        String repositoryUrl,
        String defaultBranch
) {
    public CreateTargetCommand(String name, TargetType type) {
        this(name, type, null, null);
    }
}
