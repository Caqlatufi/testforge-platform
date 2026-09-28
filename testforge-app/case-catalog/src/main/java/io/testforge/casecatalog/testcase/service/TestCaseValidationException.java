package io.testforge.casecatalog.testcase.service;

public class TestCaseValidationException extends TestCaseCatalogException {

    public TestCaseValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }
}
