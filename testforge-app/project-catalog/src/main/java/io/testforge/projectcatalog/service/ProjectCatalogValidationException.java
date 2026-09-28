package io.testforge.projectcatalog.service;

public final class ProjectCatalogValidationException extends ProjectCatalogException {

    public ProjectCatalogValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }
}
