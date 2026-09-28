package io.testforge.casecatalog.suite.ctrl;

import io.testforge.casecatalog.ctrl.ApiResponse;
import io.testforge.casecatalog.suite.model.CreateTestSuiteCommand;
import io.testforge.casecatalog.suite.model.TestSuiteView;
import io.testforge.casecatalog.suite.model.UpdateTestSuiteCommand;
import io.testforge.casecatalog.suite.service.TestSuiteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TestSuiteController {

    private final TestSuiteService service;

    public TestSuiteController(TestSuiteService service) {
        this.service = service;
    }

    @PostMapping("/projects/{projectId}/suites")
    public ResponseEntity<ApiResponse<TestSuiteView>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateTestSuiteRequest request
    ) {
        TestSuiteView suite = service.create(projectId, new CreateTestSuiteCommand(
                request.targetId(),
                request.name(),
                request.caseIds(),
                request.tags(),
                request.parameterBindings()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(suite));
    }

    @PutMapping("/suites/{suiteId}")
    public ApiResponse<TestSuiteView> update(
            @PathVariable UUID suiteId,
            @Valid @RequestBody UpdateTestSuiteRequest request
    ) {
        return ApiResponse.success(service.update(suiteId, new UpdateTestSuiteCommand(
                request.expectedVersion(),
                request.name(),
                request.caseIds(),
                request.tags(),
                request.parameterBindings()
        )));
    }

    @GetMapping("/suites/{suiteId}")
    public ApiResponse<TestSuiteView> get(@PathVariable UUID suiteId) {
        return ApiResponse.success(service.get(suiteId));
    }

    @GetMapping("/projects/{projectId}/suites")
    public ApiResponse<List<TestSuiteView>> list(
            @PathVariable UUID projectId,
            @RequestParam UUID targetId
    ) {
        return ApiResponse.success(service.list(projectId, targetId));
    }
}
