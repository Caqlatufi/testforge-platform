package io.testforge.projectcatalog.service;

public abstract class ProjectCatalogException extends RuntimeException {

    private final String code;

    protected ProjectCatalogException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
