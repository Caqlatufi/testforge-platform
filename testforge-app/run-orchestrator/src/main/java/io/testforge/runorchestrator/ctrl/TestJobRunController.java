package io.testforge.runorchestrator.ctrl;

import io.testforge.runorchestrator.job.TestJobLaunchService;
import io.testforge.runorchestrator.job.TestJobRunQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.testforge.projectcatalog.revision.RevisionSelector;
import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.runorchestrator.comparison.model.ComparisonRunView;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/v1/test-jobs")
public class TestJobRunController {
    private final TestJobLaunchService launch;
    private final TestJobRunQueryService query;
    public TestJobRunController(TestJobLaunchService launch, TestJobRunQueryService query) {
        this.launch = launch; this.query = query;
    }

    @PostMapping("/{id}/runs")
    public ResponseEntity<ApiResponse<RunResponse>> launch(@PathVariable UUID id,
                                                            @Valid @RequestBody LaunchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(RunResponse.from(launch.launch(id, request.requestKey()))));
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<ApiResponse<RunResponse>> execute(@PathVariable UUID id,
                                                             @Valid @RequestBody LaunchRequest request) {
        return launch(id, request);
    }

    @GetMapping("/{id}/runs")
    public ApiResponse<List<RunResponse>> runs(@PathVariable UUID id) {
        return ApiResponse.success(query.runs(id).stream().map(RunResponse::from).toList());
    }

    @GetMapping("/{id}/attempts")
    public ApiResponse<List<RunResponse>> attempts(@PathVariable UUID id) {
        return runs(id);
    }

    @PostMapping("/{id}/comparison-runs")
    public ResponseEntity<ApiResponse<ComparisonRunView>> comparison(
            @PathVariable UUID id, @Valid @RequestBody ComparisonLaunchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(launch.launchComparison(id,
                request.requestKey(), request.baselineRevision().selector(), request.candidateRevision().selector())));
    }

    @GetMapping("/run-summaries")
    public ApiResponse<List<TestJobRunQueryService.TestTaskExecutionSummary>> summaries(
            @RequestParam(required = false) UUID projectId) {
        return ApiResponse.success(query.summaries(projectId));
    }

    @GetMapping("/execution-summaries")
    public ApiResponse<List<TestJobRunQueryService.TestTaskExecutionSummary>> executionSummaries(
            @RequestParam(required = false) UUID projectId) {
        return ApiResponse.success(query.summaries(projectId));
    }

    public record LaunchRequest(@NotNull UUID requestKey) { }
    public record RevisionRequest(@NotNull RevisionType type, String value) {
        RevisionSelector selector() { return new RevisionSelector(type, value); }
    }
    public record ComparisonLaunchRequest(@NotNull UUID requestKey,
                                          @NotNull @Valid RevisionRequest baselineRevision,
                                          @NotNull @Valid RevisionRequest candidateRevision) { }
}
