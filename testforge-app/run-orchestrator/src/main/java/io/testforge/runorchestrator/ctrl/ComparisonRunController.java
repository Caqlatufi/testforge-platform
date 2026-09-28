package io.testforge.runorchestrator.ctrl;

import io.testforge.runorchestrator.comparison.model.CreateComparisonRunCommand;
import io.testforge.runorchestrator.comparison.model.ComparisonRunView;
import io.testforge.runorchestrator.comparison.service.ComparisonRunService;
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

@RestController
@RequestMapping("/api/v1/comparison-runs")
public class ComparisonRunController {
    private final ComparisonRunService service;

    public ComparisonRunController(ComparisonRunService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ComparisonRunView>> create(@Valid @RequestBody CreateComparisonRunRequest request) {
        int processConcurrency = request.processConcurrency() == null ? request.maxConcurrency() : request.processConcurrency();
        int deviceConcurrency = request.deviceConcurrency() == null ? request.maxConcurrency() : request.deviceConcurrency();
        ComparisonRunView view = service.create(new CreateComparisonRunCommand(
                request.projectId(), request.targetId(), request.environmentId(), request.workflowId(),
                request.workflowVersion(), request.priority(), request.maxConcurrency(), processConcurrency,
                deviceConcurrency, request.requestKey(), request.baselineRevision().toSelector(),
                request.candidateRevision().toSelector(), request.deploymentProfileId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(view));
    }

    @GetMapping("/{comparisonGroupId}")
    public ApiResponse<ComparisonRunView> get(@PathVariable UUID comparisonGroupId) {
        return ApiResponse.success(service.get(comparisonGroupId));
    }
}
