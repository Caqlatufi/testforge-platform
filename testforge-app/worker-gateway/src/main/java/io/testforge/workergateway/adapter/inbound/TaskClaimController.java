package io.testforge.workergateway.adapter.inbound;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnBean(TaskClaimService.class)
@RequestMapping("/api/v1/tasks")
public class TaskClaimController {

    private final ObjectProvider<TaskClaimService> serviceProvider;

    public TaskClaimController(ObjectProvider<TaskClaimService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @PostMapping("/{taskId}/claim")
    public Map<String, Object> claim(
            @PathVariable UUID taskId,
            @Valid @RequestBody ClaimRequest request
    ) {
        TaskClaimService service = serviceProvider.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Task claim 尚未接入运行编排器");
        }
        return Map.of("data", service.claim(
                request.runId(), taskId, request.messageId(), request.workerId()
        ));
    }

    public record ClaimRequest(
            @NotNull UUID runId,
            @NotNull UUID messageId,
            @NotBlank String workerId
    ) {
    }
}
