package io.testforge.runorchestrator.ctrl;

import io.testforge.casecatalog.workflow.service.WorkflowCatalogException;
import io.testforge.projectcatalog.service.ProjectCatalogException;
import io.testforge.runorchestrator.run.service.RunIdempotencyConflictException;
import io.testforge.runorchestrator.run.service.RunNotFoundException;
import io.testforge.runorchestrator.run.service.RunOrchestrationException;
import io.testforge.runorchestrator.run.service.RunStateConflictException;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {
        RunController.class,
        ComparisonRunController.class,
        TestJobRunController.class
})
public class RunExceptionHandler {

    @ExceptionHandler(RunOrchestrationException.class)
    ResponseEntity<ErrorResponse> handleRunException(RunOrchestrationException exception) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String code = "VALIDATION_ERROR";
        if (exception instanceof RunNotFoundException) {
            status = HttpStatus.NOT_FOUND;
            code = "RESOURCE_NOT_FOUND";
        } else if (exception instanceof RunIdempotencyConflictException) {
            status = HttpStatus.CONFLICT;
            code = "IDEMPOTENCY_CONFLICT";
        } else if (exception instanceof RunStateConflictException) {
            status = HttpStatus.CONFLICT;
            code = "STATE_CONFLICT";
        }
        return ResponseEntity.status(status).body(ErrorResponse.of(code, exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(ProjectCatalogException.class)
    ResponseEntity<ErrorResponse> handleProjectCatalogException(ProjectCatalogException exception) {
        return upstreamException(exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(WorkflowCatalogException.class)
    ResponseEntity<ErrorResponse> handleWorkflowCatalogException(WorkflowCatalogException exception) {
        return upstreamException(exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler({OptimisticLockException.class, OptimisticLockingFailureException.class})
    ResponseEntity<ErrorResponse> handleOptimisticConflict(Exception exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "STATE_CONFLICT",
                "Run 或 Task 状态已被并发更新，请重新查询后重试",
                Map.of("reason", exception.getClass().getSimpleName())
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

    private ResponseEntity<ErrorResponse> upstreamException(String code, String message) {
        HttpStatus status = switch (code) {
            case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "STATE_CONFLICT", "IDEMPOTENCY_CONFLICT" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message, Map.of()));
    }

    private ResponseEntity<ErrorResponse> validationError(String message, Map<String, Object> details) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_ERROR", message, details));
    }
}
