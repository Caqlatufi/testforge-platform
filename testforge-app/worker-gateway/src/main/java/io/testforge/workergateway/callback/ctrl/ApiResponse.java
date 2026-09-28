package io.testforge.workergateway.callback.ctrl;

import java.util.UUID;

public record ApiResponse<T>(String code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "success", data, newTraceId());
    }

    static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
