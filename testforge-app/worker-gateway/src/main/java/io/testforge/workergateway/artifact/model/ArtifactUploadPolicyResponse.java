package io.testforge.workergateway.artifact.model;

import java.time.Instant;
import java.util.Map;

public record ArtifactUploadPolicyResponse(
        String uploadUrl,
        String objectKey,
        Instant expiresAt,
        Map<String, String> fields
) {
}
