package io.testforge.cicdgateway.catalog.model;

import java.util.UUID;

public record PipelineRef(
        UUID externalId,
        String name,
        String provider,
        String serverUrl,
        String jobName,
        String revision,
        boolean enabled,
        String kind
) {
    public PipelineRef(UUID externalId, String name, String provider, String serverUrl, String jobName,
                       String revision, boolean enabled) {
        this(externalId, name, provider, serverUrl, jobName, revision, enabled, "PIPELINE");
    }
}
