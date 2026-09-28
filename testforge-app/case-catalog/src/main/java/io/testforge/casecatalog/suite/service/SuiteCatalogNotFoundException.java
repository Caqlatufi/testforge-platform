package io.testforge.casecatalog.suite.service;

public final class SuiteCatalogNotFoundException extends SuiteCatalogException {

    public SuiteCatalogNotFoundException(String message) {
        super("RESOURCE_NOT_FOUND", message);
    }
}
