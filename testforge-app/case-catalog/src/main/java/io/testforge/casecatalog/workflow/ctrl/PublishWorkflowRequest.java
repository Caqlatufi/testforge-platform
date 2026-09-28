package io.testforge.casecatalog.workflow.ctrl;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PublishWorkflowRequest(@NotNull UUID requestKey) {
}
