package io.testforge.casecatalog.workflow.service;

public final class WorkflowConflictException extends WorkflowCatalogException {

    public WorkflowConflictException(String message) {
        super("STATE_CONFLICT", message);
    }
}
