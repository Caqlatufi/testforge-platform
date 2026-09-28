package io.testforge.casecatalog.workflow.service;

public final class WorkflowNotFoundException extends WorkflowCatalogException {

    public WorkflowNotFoundException(String message) {
        super("RESOURCE_NOT_FOUND", message);
    }
}
