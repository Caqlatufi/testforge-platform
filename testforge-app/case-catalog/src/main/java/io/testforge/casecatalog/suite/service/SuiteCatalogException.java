package io.testforge.casecatalog.suite.service;

public abstract class SuiteCatalogException extends RuntimeException {

    private final String code;

    protected SuiteCatalogException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
