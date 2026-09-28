package io.testforge.casecatalog.testcase.service;

public class TestCaseConflictException extends TestCaseCatalogException {

    public TestCaseConflictException(String message) {
        super("STATE_CONFLICT", message);
    }
}
