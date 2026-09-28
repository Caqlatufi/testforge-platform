package io.testforge.projectcatalog.ctrl;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.testforge.projectcatalog.model.TargetType;

public record CreateProjectRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 63) String code,
        TargetType targetType,
        @Size(max = 2048) String repositoryUrl,
        @Size(max = 255) String defaultBranch,
        @Size(max = 32) String ciProvider,
        @Size(max = 2048) String ciServerUrl,
        @Size(max = 512) String ciFolder,
        @Size(max = 255) String ciCredentialRef
) {
}
