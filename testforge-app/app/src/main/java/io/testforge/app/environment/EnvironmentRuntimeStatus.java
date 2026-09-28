package io.testforge.app.environment;

import java.util.UUID;

public record EnvironmentRuntimeStatus(
        UUID environmentId,
        String provider,
        String state,
        boolean controllable,
        String message
) {
}
