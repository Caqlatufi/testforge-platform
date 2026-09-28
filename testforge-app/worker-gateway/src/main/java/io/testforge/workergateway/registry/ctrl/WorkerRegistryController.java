package io.testforge.workergateway.registry.ctrl;

import io.testforge.workergateway.registry.model.RegisterWorkerCommand;
import io.testforge.workergateway.registry.model.WorkerNodeView;
import io.testforge.workergateway.registry.model.WorkerRequirement;
import io.testforge.workergateway.registry.model.WorkerStatus;
import io.testforge.workergateway.registry.service.WorkerRegistryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/workers")
public class WorkerRegistryController {

    private final WorkerRegistryService service;

    public WorkerRegistryController(WorkerRegistryService service) {
        this.service = service;
    }

    @PostMapping("/register")
    public WorkerRegistryResponse<WorkerNodeView> register(
            @Valid @RequestBody RegisterWorkerRequest request
    ) {
        return WorkerRegistryResponse.success(service.register(new RegisterWorkerCommand(
                request.workerId(),
                request.protocolVersion(),
                request.capabilities(),
                request.maxConcurrency()
        )));
    }

    @PostMapping("/{workerId}/heartbeat")
    public WorkerRegistryResponse<WorkerNodeView> heartbeat(@PathVariable String workerId) {
        return WorkerRegistryResponse.success(service.heartbeat(workerId));
    }

    @GetMapping("/{workerId}")
    public WorkerRegistryResponse<WorkerNodeView> get(@PathVariable String workerId) {
        return WorkerRegistryResponse.success(service.get(workerId));
    }

    @GetMapping
    public WorkerRegistryResponse<List<WorkerNodeView>> list(
            @RequestParam(required = false) WorkerStatus status
    ) {
        return WorkerRegistryResponse.success(service.list(status));
    }

    @GetMapping("/match")
    public WorkerRegistryResponse<List<WorkerNodeView>> match(
            @RequestParam(defaultValue = "1.0") String protocolVersion,
            @RequestParam String runner,
            @RequestParam(defaultValue = "ANY") String platform,
            @RequestParam(required = false) Set<String> requiredFeatures
    ) {
        return WorkerRegistryResponse.success(service.findMatching(new WorkerRequirement(
                protocolVersion,
                runner,
                platform,
                requiredFeatures
        )));
    }

    public record RegisterWorkerRequest(
            @NotBlank @Size(max = 128) String workerId,
            @NotBlank @Pattern(regexp = "^1\\.[0-9]+$") String protocolVersion,
            @NotEmpty @Size(max = 64) Set<
                    @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String> capabilities,
            @Min(1) @Max(128) int maxConcurrency
    ) {
    }
}
