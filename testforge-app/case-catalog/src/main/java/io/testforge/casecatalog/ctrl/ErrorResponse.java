package io.testforge.casecatalog.ctrl;

import java.util.Map;

/**
 * case-catalog 所有 REST API 共用的错误响应信封。
 */
public record ErrorResponse(
        String code,
        String message,
        Map<String, Object> details,
        String traceId
) {

    public static ErrorResponse of(String code, String message, Map<String, Object> details) {
        return new ErrorResponse(code, message, Map.copyOf(details), ApiResponse.newTraceId());
    }
}
