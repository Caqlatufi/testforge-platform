package io.testforge.runorchestrator.ctrl;

import io.testforge.runorchestrator.run.model.CancelRunCommand;
import io.testforge.runorchestrator.run.model.CreateRunCommand;
import io.testforge.runorchestrator.run.service.RunTaskService;
import io.testforge.runorchestrator.run.service.RunValidationException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunTaskService service;

    public RunController(RunTaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RunResponse>> create(
            @Valid @RequestBody CreateRunRequest request
    ) {
        Map<UUID, io.testforge.projectcatalog.revision.RevisionSelector> overrides = new LinkedHashMap<>();
        if (request.taskRevisionOverrides() != null) {
            for (TaskRevisionOverrideRequest override : request.taskRevisionOverrides()) {
                if (overrides.putIfAbsent(override.workflowNodeId(), override.revision().toSelector()) != null) {
                    throw new RunValidationException("taskRevisionOverrides 包含重复 workflowNodeId");
                }
            }
        }
        var run = service.createRun(new CreateRunCommand(
                request.projectId(),
                request.targetId(),
                request.environmentId(),
                request.workflowId(),
                request.workflowVersion(),
                request.priority(),
                request.maxConcurrency(),
                request.processConcurrency() == null ? request.maxConcurrency() : request.processConcurrency(),
                request.deviceConcurrency() == null ? request.maxConcurrency() : request.deviceConcurrency(),
                request.requestKey(),
                request.targetRevision() == null ? null : request.targetRevision().toSelector(),
                overrides,
                request.deploymentProfileId()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(RunResponse.from(run)));
    }

    @GetMapping("/{runId}")
    public ApiResponse<RunResponse> get(@PathVariable UUID runId) {
        return ApiResponse.success(RunResponse.from(service.getRun(runId)));
    }

    @GetMapping
    public ApiResponse<List<RunResponse>> list() {
        return ApiResponse.success(service.listRuns().stream().map(RunResponse::from).toList());
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<ApiResponse<RunResponse>> cancel(
            @PathVariable UUID runId,
            @Valid @RequestBody CancelRunRequest request
    ) {
        var run = service.requestCancellation(
                runId,
                new CancelRunCommand(request.requestKey(), request.reason())
        );
        return ResponseEntity.accepted().body(ApiResponse.success(RunResponse.from(run)));
    }
}
