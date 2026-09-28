package io.testforge.projectcatalog.service;

public final class ProjectCatalogConflictException extends ProjectCatalogException {

    public ProjectCatalogConflictException(String message) {
        super("STATE_CONFLICT", message);
    }
}
