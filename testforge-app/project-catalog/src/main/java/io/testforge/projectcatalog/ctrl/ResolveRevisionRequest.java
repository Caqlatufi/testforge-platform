package io.testforge.projectcatalog.ctrl;

import io.testforge.projectcatalog.revision.RevisionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResolveRevisionRequest(
        @NotNull RevisionType type,
        @Size(max = 255) String value
) {
}
