package io.testforge.runorchestrator.ctrl;

import io.testforge.projectcatalog.revision.RevisionSelector;
import io.testforge.projectcatalog.revision.RevisionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TargetRevisionRequest(
        @NotNull RevisionType type,
        @Size(max = 255) String value
) {
    RevisionSelector toSelector() {
        return new RevisionSelector(type, value);
    }
}
