package io.testforge.app.environment;

import io.testforge.projectcatalog.ctrl.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/environments/runtime")
public class EnvironmentRuntimeController {
    private final EnvironmentRuntimeService service;

    public EnvironmentRuntimeController(EnvironmentRuntimeService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<EnvironmentRuntimeStatus>> list() {
        return ApiResponse.success(service.list());
    }

    @PostMapping("/{environmentId}/start")
    public ApiResponse<EnvironmentRuntimeStatus> start(@PathVariable UUID environmentId) {
        return ApiResponse.success(service.start(environmentId));
    }
}
