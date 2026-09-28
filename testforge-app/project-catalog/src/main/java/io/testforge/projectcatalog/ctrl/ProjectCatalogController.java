package io.testforge.projectcatalog.ctrl;

import io.testforge.projectcatalog.model.CreateEnvironmentCommand;
import io.testforge.projectcatalog.model.CreateProjectCommand;
import io.testforge.projectcatalog.model.CreateTargetCommand;
import io.testforge.projectcatalog.model.EnvironmentView;
import io.testforge.projectcatalog.model.EnvironmentPlatform;
import io.testforge.projectcatalog.model.ProjectView;
import io.testforge.projectcatalog.model.TargetView;
import io.testforge.projectcatalog.revision.ResolvedRevision;
import io.testforge.projectcatalog.revision.RevisionSelector;
import io.testforge.projectcatalog.revision.RevisionOption;
import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.projectcatalog.service.ProjectCatalogService;
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
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ProjectCatalogController {

    private final ProjectCatalogService service;

    public ProjectCatalogController(ProjectCatalogService service) {
        this.service = service;
    }

    @PostMapping("/projects")
    public ResponseEntity<ApiResponse<ProjectView>> createProject(
            @Valid @RequestBody CreateProjectRequest request
    ) {
        ProjectView project = service.createProject(toCommand(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(project));
    }

    @GetMapping("/projects")
    public ApiResponse<List<ProjectView>> listProjects() {
        return ApiResponse.success(service.listProjects());
    }

    @PutMapping("/projects/{projectId}")
    public ApiResponse<ProjectView> updateProject(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateProjectRequest request
    ) {
        return ApiResponse.success(service.updateProject(
                projectId, toCommand(request)
        ));
    }

    private CreateProjectCommand toCommand(CreateProjectRequest request) {
        return new CreateProjectCommand(request.name(), request.code(), request.targetType(),
                request.repositoryUrl(), request.defaultBranch(), request.ciProvider(), request.ciServerUrl(),
                request.ciFolder(), request.ciCredentialRef());
    }

    @PostMapping("/projects/{projectId}/targets")
    public ResponseEntity<ApiResponse<TargetView>> createTarget(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateTargetRequest request
    ) {
        TargetView target = service.createTarget(projectId, new CreateTargetCommand(
                request.name(), request.type(), request.repositoryUrl(), request.defaultBranch()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(target));
    }

    @GetMapping("/environments")
    public ApiResponse<List<EnvironmentView>> listEnvironments(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) EnvironmentPlatform platform
    ) {
        return ApiResponse.success(service.listEnvironments(enabled, platform));
    }

    @PostMapping("/environments")
    public ResponseEntity<ApiResponse<EnvironmentView>> createEnvironment(
            @Valid @RequestBody CreateEnvironmentRequest request
    ) {
        EnvironmentView environment = service.createEnvironment(
                new CreateEnvironmentCommand(
                        request.name(),
                        request.endpoint(),
                        request.providerEnvironmentKey(),
                        request.platform(),
                        request.resourcePoolKey(),
                        request.capacity(),
                        request.enabled(),
                        request.initializeOnNextDeploy(),
                        request.config(),
                        request.secretRefs()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(environment));
    }

    @PutMapping("/targets/{targetId}")
    public ApiResponse<TargetView> updateTarget(
            @PathVariable UUID targetId,
            @Valid @RequestBody CreateTargetRequest request
    ) {
        return ApiResponse.success(service.updateTarget(
                targetId, new CreateTargetCommand(
                        request.name(), request.type(), request.repositoryUrl(), request.defaultBranch()
                )
        ));
    }

    @PostMapping("/targets/{targetId}/revisions/resolve")
    public ApiResponse<ResolvedRevision> resolveRevision(
            @PathVariable UUID targetId,
            @Valid @RequestBody ResolveRevisionRequest request
    ) {
        return ApiResponse.success(service.resolveRevision(
                targetId,
                new RevisionSelector(request.type(), request.value())
        ));
    }

    @PostMapping("/projects/{projectId}/revisions/resolve")
    public ApiResponse<ResolvedRevision> resolveProjectRevision(
            @PathVariable UUID projectId,
            @Valid @RequestBody ResolveRevisionRequest request
    ) {
        return ApiResponse.success(service.resolveProjectRevision(
                projectId,
                new RevisionSelector(request.type(), request.value())
        ));
    }

    @GetMapping("/projects/{projectId}/revisions")
    public ApiResponse<List<RevisionOption>> listProjectRevisions(
            @PathVariable UUID projectId,
            @RequestParam RevisionType type
    ) {
        return ApiResponse.success(service.listProjectRevisions(projectId, type));
    }

    @PutMapping("/environments/{environmentId}")
    public ApiResponse<EnvironmentView> updateEnvironment(
            @PathVariable UUID environmentId,
            @Valid @RequestBody CreateEnvironmentRequest request
    ) {
        return ApiResponse.success(service.updateEnvironment(
                environmentId,
                new CreateEnvironmentCommand(request.name(), request.endpoint(), request.providerEnvironmentKey(),
                        request.platform(), request.resourcePoolKey(),
                        request.capacity(), request.enabled(), request.initializeOnNextDeploy(),
                        request.config(), request.secretRefs())
        ));
    }

    @GetMapping("/projects/{projectId}")
    public ApiResponse<ProjectView> getProject(@PathVariable UUID projectId) {
        return ApiResponse.success(service.getProject(projectId));
    }
}
