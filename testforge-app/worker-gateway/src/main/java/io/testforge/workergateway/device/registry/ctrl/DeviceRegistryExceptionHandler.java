package io.testforge.workergateway.device.registry.ctrl;

import io.testforge.workergateway.device.registry.service.DeviceRegistryValidationException;
import io.testforge.workergateway.device.registry.service.DeviceSlotNotFoundException;
import io.testforge.workergateway.registry.service.WorkerNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = DeviceRegistryController.class)
public class DeviceRegistryExceptionHandler {

    @ExceptionHandler({DeviceSlotNotFoundException.class, WorkerNotFoundException.class})
    ResponseEntity<DeviceRegistryResponse<Void>> notFound(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(DeviceRegistryResponse.error(
                        "RESOURCE_NOT_FOUND",
                        exception.getMessage(),
                        Map.of()
                ));
    }

    @ExceptionHandler(DeviceRegistryValidationException.class)
    ResponseEntity<DeviceRegistryResponse<Void>> validation(DeviceRegistryValidationException exception) {
        return ResponseEntity.badRequest()
                .body(DeviceRegistryResponse.error(
                        "VALIDATION_ERROR",
                        exception.getMessage(),
                        Map.of()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<DeviceRegistryResponse<Void>> requestValidation(MethodArgumentNotValidException exception) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                details.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        return ResponseEntity.badRequest().body(DeviceRegistryResponse.error(
                "VALIDATION_ERROR",
                "请求字段校验失败",
                details
        ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<DeviceRegistryResponse<Void>> constraintValidation(
            ConstraintViolationException exception
    ) {
        return ResponseEntity.badRequest().body(DeviceRegistryResponse.error(
                "VALIDATION_ERROR",
                exception.getMessage(),
                Map.of()
        ));
    }
}
