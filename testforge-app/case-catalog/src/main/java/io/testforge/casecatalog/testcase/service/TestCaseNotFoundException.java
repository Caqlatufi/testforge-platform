package io.testforge.casecatalog.testcase.service;

public class TestCaseNotFoundException extends TestCaseCatalogException {

    public TestCaseNotFoundException(String message) {
        super("RESOURCE_NOT_FOUND", message);
    }
}
