package io.testforge.workergateway.callback.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

public record FailurePayload(
        @NotBlank @Pattern(regexp = "PRODUCT_DEFECT|SCRIPT_ERROR|ENVIRONMENT|WORKER_LOST|DEVICE_ERROR|IMAGE_MATCH_TIMEOUT|CANCELLED|UNKNOWN") String type,
        @NotBlank @Size(max = 4000) String message,
        @Size(max = 4000) String stackDigest,
        Boolean retryable,
        Map<String, Object> details
) {
    public FailurePayload {
        details = details == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(details));
    }
}
