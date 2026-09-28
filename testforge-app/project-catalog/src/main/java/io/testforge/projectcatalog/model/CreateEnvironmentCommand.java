package io.testforge.projectcatalog.model;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record CreateEnvironmentCommand(
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
    public CreateEnvironmentCommand(String name, String endpoint, String providerEnvironmentKey,
                                    int capacity, boolean enabled, boolean initializeOnNextDeploy,
                                    Map<String, Object> config, Map<String, String> secretRefs) {
        this(name, endpoint, providerEnvironmentKey, legacyPlatform(config),
                legacyPoolKey(config), capacity, enabled, initializeOnNextDeploy, config, secretRefs);
    }

    public CreateEnvironmentCommand(String name, String endpoint, Map<String, Object> config,
                                    Map<String, String> secretRefs) {
        this(name, endpoint, legacyProviderKey(name), EnvironmentPlatform.WINDOWS, "windows", 1,
                true, false, config, secretRefs);
    }

    private static String legacyProviderKey(String name) {
        String source = name == null ? "environment" : name;
        return "env-" + UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private static EnvironmentPlatform legacyPlatform(Map<String, Object> config) {
        Object value = config == null ? null : config.get("platform");
        if (value == null) return EnvironmentPlatform.WINDOWS;
        try { return EnvironmentPlatform.valueOf(value.toString().trim().toUpperCase()); }
        catch (RuntimeException ignored) { return EnvironmentPlatform.WINDOWS; }
    }

    private static String legacyPoolKey(Map<String, Object> config) {
        Object value = config == null ? null : config.get("resourcePoolKey");
        return value == null || value.toString().isBlank()
                ? legacyPlatform(config).name().toLowerCase()
                : value.toString().trim();
    }
}
