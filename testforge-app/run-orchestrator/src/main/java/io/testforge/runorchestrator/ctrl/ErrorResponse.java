package io.testforge.runorchestrator.ctrl;

import java.util.Map;

public record ErrorResponse(
        String code,
        String message,
        Map<String, Object> details,
        String traceId
) {

    static ErrorResponse of(String code, String message, Map<String, Object> details) {
        return new ErrorResponse(code, message, Map.copyOf(details), ApiResponse.newTraceId());
    }
}
