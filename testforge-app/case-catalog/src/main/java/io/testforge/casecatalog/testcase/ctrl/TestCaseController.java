package io.testforge.casecatalog.testcase.ctrl;

import io.testforge.casecatalog.ctrl.ApiResponse;
import io.testforge.casecatalog.testcase.model.CreateScriptVersionCommand;
import io.testforge.casecatalog.testcase.model.CreateTestCaseCommand;
import io.testforge.casecatalog.testcase.model.CaseDefinitionView;
import io.testforge.casecatalog.testcase.model.ScriptVersionView;
import io.testforge.casecatalog.testcase.model.TestCaseView;
import io.testforge.casecatalog.testcase.service.TestCaseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TestCaseController {

    private final TestCaseService service;

    public TestCaseController(TestCaseService service) {
        this.service = service;
    }

    @PostMapping("/projects/{projectId}/cases")
    public ResponseEntity<ApiResponse<TestCaseView>> createTestCase(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateTestCaseRequest request
    ) {
        TestCaseView testCase = service.createTestCase(projectId, new CreateTestCaseCommand(
                request.targetId(),
                request.name(),
                request.kind(),
                request.scope(),
                request.parameters(),
                request.tags(),
                request.timeoutSeconds(),
                request.executionRequirement()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(testCase));
    }

    @GetMapping("/projects/{projectId}/cases")
    public ApiResponse<List<TestCaseView>> listTestCases(@PathVariable UUID projectId) {
        return ApiResponse.success(service.listTestCases(projectId));
    }

    @PostMapping("/projects/{projectId}/case-definitions/validate")
    public ApiResponse<CaseDefinitionView> validateDefinition(
            @PathVariable UUID projectId,
            @Valid @RequestBody CaseDefinitionRequest request
    ) {
        return ApiResponse.success(service.validateDefinition(projectId, request.yaml()));
    }

    @PostMapping("/projects/{projectId}/case-definitions")
    public ResponseEntity<ApiResponse<TestCaseView>> createDefinition(
            @PathVariable UUID projectId,
            @Valid @RequestBody CaseDefinitionRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.createDefinition(projectId, request.yaml())));
    }

    @GetMapping("/cases/shared")
    public ApiResponse<List<TestCaseView>> listSharedTestCases() {
        return ApiResponse.success(service.listSharedTestCases());
    }

    @GetMapping("/cases/{caseId}")
    public ApiResponse<TestCaseView> getTestCase(@PathVariable UUID caseId) {
        return ApiResponse.success(service.getTestCase(caseId));
    }

    @GetMapping("/cases/{caseId}/definition")
    public ApiResponse<CaseDefinitionView> getDefinition(@PathVariable UUID caseId) {
        return ApiResponse.success(service.getDefinition(caseId));
    }

    @PutMapping("/cases/{caseId}/definition")
    public ApiResponse<TestCaseView> updateDefinition(
            @PathVariable UUID caseId,
            @Valid @RequestBody CaseDefinitionRequest request
    ) {
        return ApiResponse.success(service.updateDefinition(caseId, request.yaml()));
    }

    @PutMapping("/cases/{caseId}")
    public ApiResponse<TestCaseView> updateTestCase(
            @PathVariable UUID caseId,
            @Valid @RequestBody CreateTestCaseRequest request
    ) {
        return ApiResponse.success(service.updateTestCase(caseId, new CreateTestCaseCommand(
                request.targetId(), request.name(), request.kind(), request.scope(), request.parameters(),
                request.tags(), request.timeoutSeconds(), request.executionRequirement()
        )));
    }

    @PostMapping("/cases/{caseId}/scripts")
    public ResponseEntity<ApiResponse<ScriptVersionView>> createScriptVersion(
            @PathVariable UUID caseId,
            @Valid @RequestBody CreateScriptVersionRequest request
    ) {
        ScriptVersionView version = service.createScriptVersion(caseId, new CreateScriptVersionCommand(
                request.runner(),
                request.sourceRef(),
                request.checksum()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(version));
    }

    @GetMapping("/cases/{caseId}/scripts")
    public ApiResponse<List<ScriptVersionView>> listScriptVersions(@PathVariable UUID caseId) {
        return ApiResponse.success(service.listScriptVersions(caseId));
    }
}
