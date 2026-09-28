package io.testforge.casecatalog.testcase.service;

public abstract class TestCaseCatalogException extends RuntimeException {

    private final String code;

    protected TestCaseCatalogException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
