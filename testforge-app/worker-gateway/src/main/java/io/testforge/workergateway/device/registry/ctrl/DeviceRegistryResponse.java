package io.testforge.workergateway.device.registry.ctrl;

import java.util.UUID;

public record DeviceRegistryResponse<T>(
        String code,
        String message,
        T data,
        Object details,
        String traceId
) {

    public static <T> DeviceRegistryResponse<T> success(T data) {
        return new DeviceRegistryResponse<>("OK", "success", data, null, traceIdValue());
    }

    static DeviceRegistryResponse<Void> error(String code, String message, Object details) {
        return new DeviceRegistryResponse<>(code, message, null, details, traceIdValue());
    }

    private static String traceIdValue() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
