package io.testforge.cicdgateway.catalog.model;

public record PipelineEnvironmentRef(String externalId, String name, String providerKey,
                                     String platform, String resourcePoolKey, int capacity) {
    public PipelineEnvironmentRef(String externalId, String name, String providerKey) {
        this(externalId, name, providerKey, "WINDOWS", "windows", 1);
    }

    public PipelineEnvironmentRef(String externalId, String name) {
        this(externalId, name, externalId);
    }
}
