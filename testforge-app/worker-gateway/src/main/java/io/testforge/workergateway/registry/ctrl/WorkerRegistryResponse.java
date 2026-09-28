package io.testforge.workergateway.registry.ctrl;

import java.util.UUID;

public record WorkerRegistryResponse<T>(
        String code,
        String message,
        T data,
        Object details,
        String traceId
) {
    static <T> WorkerRegistryResponse<T> success(T data) {
        return new WorkerRegistryResponse<>("OK", "success", data, null, traceIdValue());
    }

    static WorkerRegistryResponse<Void> error(String code, String message, Object details) {
        return new WorkerRegistryResponse<>(code, message, null, details, traceIdValue());
    }

    private static String traceIdValue() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
