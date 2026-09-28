package io.testforge.projectcatalog.revision;

import io.testforge.projectcatalog.service.ProjectCatalogException;

public final class RevisionResolutionException extends ProjectCatalogException {

    public RevisionResolutionException(String code, String message) {
        super(code, message);
    }
}
