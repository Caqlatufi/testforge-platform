package io.testforge.casecatalog.suite.service;

public final class SuiteCatalogValidationException extends SuiteCatalogException {

    public SuiteCatalogValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }
}
