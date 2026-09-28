package io.testforge.workergateway.callback.ctrl;

import io.testforge.workergateway.artifact.service.ArtifactUploadUnavailableException;
import io.testforge.workergateway.callback.service.AttemptLeaseExpiredException;
import io.testforge.workergateway.callback.service.CallbackIdempotencyConflictException;
import io.testforge.workergateway.callback.service.CallbackValidationException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = AttemptController.class)
public class AttemptCallbackExceptionHandler {

    @ExceptionHandler(ArtifactUploadUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleArtifactUploadUnavailable(
            ArtifactUploadUnavailableException exception
    ) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse.of(
                "ARTIFACT_UPLOAD_UNAVAILABLE", exception.getMessage(), Map.of()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "VALIDATION_ERROR", exception.getMessage(), Map.of()
        ));
    }

    @ExceptionHandler(AttemptLeaseExpiredException.class)
    public ResponseEntity<ErrorResponse> handleLeaseExpired(AttemptLeaseExpiredException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "LEASE_EXPIRED",
                exception.getMessage(),
                Map.of("attemptId", exception.getAttemptId())
        ));
    }

    @ExceptionHandler(CallbackIdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(
            CallbackIdempotencyConflictException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "IDEMPOTENCY_CONFLICT",
                exception.getMessage(),
                Map.of(
                        "attemptId", exception.getAttemptId(),
                        "callbackKey", exception.getCallbackKey()
                )
        ));
    }

    @ExceptionHandler(CallbackValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(CallbackValidationException exception) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "VALIDATION_ERROR", exception.getMessage(), Map.of()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException exception) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            details.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "VALIDATION_ERROR", "请求字段校验失败", details
        ));
    }

    @ExceptionHandler({ConstraintViolationException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception exception) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "VALIDATION_ERROR",
                "请求内容不合法",
                Map.of("reason", exception.getClass().getSimpleName())
        ));
    }
}
