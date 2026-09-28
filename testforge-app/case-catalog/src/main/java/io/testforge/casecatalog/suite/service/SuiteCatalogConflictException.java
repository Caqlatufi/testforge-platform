package io.testforge.casecatalog.suite.service;

public final class SuiteCatalogConflictException extends SuiteCatalogException {

    public SuiteCatalogConflictException(String message) {
        super("STATE_CONFLICT", message);
    }
}
