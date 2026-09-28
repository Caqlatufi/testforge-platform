package io.testforge.casecatalog.workflow.service;

public final class WorkflowValidationException extends WorkflowCatalogException {

    public WorkflowValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }
}
