package io.testforge.casecatalog.workflow.compile.service;

public final class PublishedVersionNotFoundException extends WorkflowCompileException {

    public PublishedVersionNotFoundException(String message) {
        super(message);
    }
}
