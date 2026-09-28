package io.testforge.aidiagnosis.ctrl;

import io.testforge.aidiagnosis.service.AiDiagnosisException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = {AiDiagnosisController.class, AiDiagnosisStatusController.class})
public class AiDiagnosisExceptionHandler {
    @ExceptionHandler(AiDiagnosisException.class)
    ResponseEntity<Map<String, Object>> diagnosis(AiDiagnosisException exception) {
        return ResponseEntity.status(exception.status()).body(error(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", "requestKey 不能为空"));
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("code", code, "message", message, "data", Map.of(), "traceId", UUID.randomUUID().toString());
    }
}
