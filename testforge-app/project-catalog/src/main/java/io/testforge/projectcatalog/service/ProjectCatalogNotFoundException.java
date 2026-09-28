package io.testforge.projectcatalog.service;

public final class ProjectCatalogNotFoundException extends ProjectCatalogException {

    public ProjectCatalogNotFoundException(String message) {
        super("RESOURCE_NOT_FOUND", message);
    }
}
