package io.testforge.casecatalog.ctrl;

import java.util.UUID;

/**
 * case-catalog 所有 REST API 共用的成功响应信封。
 */
public record ApiResponse<T>(String code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "success", data, newTraceId());
    }

    static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
