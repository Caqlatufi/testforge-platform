package io.testforge.testjob.ctrl;

import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.projectcatalog.model.EnvironmentPlatform;
import io.testforge.testjob.model.TestJobCommand;
import io.testforge.testjob.model.TestJobView;
import io.testforge.testjob.service.TestJobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/test-jobs")
public class TestJobController {
    private final TestJobService service;
    public TestJobController(TestJobService service) { this.service = service; }

    @GetMapping
    public Map<String, List<TestJobView>> list(@RequestParam(required = false) UUID projectId) {
        return Map.of("data", service.list(projectId));
    }

    @GetMapping("/{id}")
    public Map<String, TestJobView> get(@PathVariable UUID id) { return Map.of("data", service.get(id)); }

    @PostMapping
    public ResponseEntity<Map<String, TestJobView>> create(@Valid @RequestBody SaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", service.create(request.command())));
    }

    @PutMapping("/{id}")
    public Map<String, TestJobView> update(@PathVariable UUID id, @Valid @RequestBody SaveRequest request) {
        return Map.of("data", service.update(id, request.configVersion(), request.command()));
    }

    @PostMapping("/{id}/activate")
    public Map<String, TestJobView> activate(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return Map.of("data", service.activate(id, request.configVersion()));
    }

    @PostMapping("/{id}/disable")
    public Map<String, TestJobView> disable(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return Map.of("data", service.disable(id, request.configVersion()));
    }

    @PostMapping("/{id}/copies")
    public ResponseEntity<Map<String, TestJobView>> copy(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", service.copy(id)));
    }

    public record VersionRequest(@Min(1) long configVersion) { }

    public record SaveRequest(
            @NotNull UUID projectId,
            @Size(max = 63) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotNull UUID workflowId,
            Integer workflowVersion,
            RevisionType revisionType,
            @Size(max = 255) String revisionValue,
            @Size(max = 255) String pipelineExternalId,
            @Size(max = 255) String environmentExternalId,
            EnvironmentPlatform platform,
            Integer priority,
            Integer processConcurrency,
            Integer deviceConcurrency,
            @Min(0) long configVersion
    ) {
        TestJobCommand command() {
            return new TestJobCommand(projectId, code, name, description, workflowId,
                    workflowVersion == null ? 0 : workflowVersion,
                    revisionType, revisionValue, pipelineExternalId, environmentExternalId, platform,
                    priority == null ? 5 : priority,
                    processConcurrency == null ? 0 : processConcurrency,
                    deviceConcurrency == null ? 0 : deviceConcurrency);
        }
    }
}
