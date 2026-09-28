package io.testforge.projectcatalog.ctrl;

import io.testforge.projectcatalog.model.TargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTargetRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull TargetType type,
        @Size(max = 2048) String repositoryUrl,
        @Size(max = 255) String defaultBranch
) {
}
