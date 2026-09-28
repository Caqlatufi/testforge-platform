package io.testforge.casecatalog.testcase.asset.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CaseAssetView(
        UUID id,
        UUID projectId,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        String uri,
        List<String> entrypoints,
        String yamlSnippet,
        Instant createdAt
) {
    public CaseAssetView {
        entrypoints = List.copyOf(entrypoints);
    }
}
