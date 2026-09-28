package io.testforge.projectcatalog.model;

import java.util.Map;
import java.util.UUID;

public record EnvironmentView(
        UUID id,
        UUID targetId,
        String name,
        String endpoint,
        String providerEnvironmentKey,
        EnvironmentPlatform platform,
        String resourcePoolKey,
        int capacity,
        boolean enabled,
        boolean initializeOnNextDeploy,
        Map<String, Object> config,
        Map<String, String> secretRefs
) {
    public EnvironmentView(UUID id, UUID targetId, String name, String endpoint,
                           String providerEnvironmentKey, int capacity, boolean enabled,
                           boolean initializeOnNextDeploy, Map<String, Object> config,
                           Map<String, String> secretRefs) {
        this(id, targetId, name, endpoint, providerEnvironmentKey, EnvironmentPlatform.WINDOWS,
                "windows", capacity, enabled, initializeOnNextDeploy, config, secretRefs);
    }
}
