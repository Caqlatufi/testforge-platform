package io.testforge.workergateway.registry.ctrl;

import io.testforge.workergateway.registry.service.WorkerNotFoundException;
import io.testforge.workergateway.registry.service.WorkerRegistryException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = WorkerRegistryController.class)
public class WorkerRegistryExceptionHandler {

    @ExceptionHandler(WorkerRegistryException.class)
    ResponseEntity<WorkerRegistryResponse<Void>> handleRegistryException(WorkerRegistryException exception) {
        HttpStatus status = exception instanceof WorkerNotFoundException
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(WorkerRegistryResponse.error(
                exception.code(),
                exception.getMessage(),
                Map.of()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<WorkerRegistryResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                details.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        return ResponseEntity.badRequest().body(WorkerRegistryResponse.error(
                "VALIDATION_ERROR",
                "请求字段校验失败",
                details
        ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<WorkerRegistryResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest().body(WorkerRegistryResponse.error(
                "VALIDATION_ERROR",
                exception.getMessage(),
                Map.of()
        ));
    }
}
