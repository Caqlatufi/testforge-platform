package io.testforge.projectcatalog.ctrl;

import io.testforge.projectcatalog.service.ProjectCatalogConflictException;
import io.testforge.projectcatalog.service.ProjectCatalogException;
import io.testforge.projectcatalog.service.ProjectCatalogNotFoundException;
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

@RestControllerAdvice(assignableTypes = ProjectCatalogController.class)
public class ProjectCatalogExceptionHandler {

    @ExceptionHandler(ProjectCatalogException.class)
    ResponseEntity<ErrorResponse> handleCatalogException(ProjectCatalogException exception) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        if (exception instanceof ProjectCatalogNotFoundException) {
            status = HttpStatus.NOT_FOUND;
        } else if (exception instanceof ProjectCatalogConflictException) {
            status = HttpStatus.CONFLICT;
        }
        return ResponseEntity.status(status).body(ErrorResponse.of(
                exception.getCode(),
                exception.getMessage(),
                Map.of()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException exception) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            details.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return validationError("请求字段校验失败", details);
    }

    @ExceptionHandler({ConstraintViolationException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> handleMalformedRequest(Exception exception) {
        return validationError("请求内容不合法", Map.of("reason", exception.getClass().getSimpleName()));
    }

    private ResponseEntity<ErrorResponse> validationError(String message, Map<String, Object> details) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_ERROR", message, details));
    }
}
