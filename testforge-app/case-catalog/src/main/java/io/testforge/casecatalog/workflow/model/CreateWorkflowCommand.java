package io.testforge.casecatalog.workflow.model;

import java.util.UUID;

public record CreateWorkflowCommand(UUID targetId, String name) {
}
