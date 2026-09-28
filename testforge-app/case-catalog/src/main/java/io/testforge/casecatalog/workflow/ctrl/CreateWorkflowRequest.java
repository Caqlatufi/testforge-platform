package io.testforge.casecatalog.workflow.ctrl;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateWorkflowRequest(
        UUID targetId,
        @NotBlank @Size(max = 200) String name
) {
}
