package io.testforge.casecatalog.workflow.service;

public abstract class WorkflowCatalogException extends RuntimeException {

    private final String code;

    protected WorkflowCatalogException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
