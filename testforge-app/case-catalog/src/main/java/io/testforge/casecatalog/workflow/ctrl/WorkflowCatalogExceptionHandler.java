package io.testforge.casecatalog.workflow.ctrl;

import io.testforge.casecatalog.ctrl.ErrorResponse;
import io.testforge.casecatalog.workflow.draft.WorkflowDraftValidationException;
import io.testforge.casecatalog.workflow.service.WorkflowCatalogException;
import io.testforge.casecatalog.workflow.service.WorkflowConflictException;
import io.testforge.casecatalog.workflow.service.WorkflowNotFoundException;
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

@RestControllerAdvice(assignableTypes = TestWorkflowController.class)
public class WorkflowCatalogExceptionHandler {

    @ExceptionHandler(WorkflowCatalogException.class)
    ResponseEntity<ErrorResponse> handleCatalogException(WorkflowCatalogException exception) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        if (exception instanceof WorkflowNotFoundException) {
            status = HttpStatus.NOT_FOUND;
        } else if (exception instanceof WorkflowConflictException) {
            status = HttpStatus.CONFLICT;
        }
        return ResponseEntity.status(status).body(ErrorResponse.of(
                exception.getCode(),
                exception.getMessage(),
                Map.of()
        ));
    }

    @ExceptionHandler(WorkflowDraftValidationException.class)
    ResponseEntity<ErrorResponse> handleDraftValidation(WorkflowDraftValidationException exception) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "VALIDATION_ERROR",
                exception.getMessage(),
                Map.of("violations", exception.violations())
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException exception) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            details.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "VALIDATION_ERROR", "请求字段校验失败", details
        ));
    }

    @ExceptionHandler({ConstraintViolationException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> handleMalformedRequest(Exception exception) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "VALIDATION_ERROR",
                "请求内容不合法",
                Map.of("reason", exception.getClass().getSimpleName())
        ));
    }
}
