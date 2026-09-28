package io.testforge.aidiagnosis.ctrl;

import java.util.UUID;

public record ApiResponse<T>(String code, String message, T data, String traceId) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "success", data, UUID.randomUUID().toString());
    }
}
