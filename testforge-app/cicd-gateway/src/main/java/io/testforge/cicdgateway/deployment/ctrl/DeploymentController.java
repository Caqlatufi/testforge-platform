package io.testforge.cicdgateway.deployment.ctrl;

import io.testforge.cicdgateway.deployment.model.*;
import io.testforge.cicdgateway.deployment.service.DeploymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DeploymentController {
    private final DeploymentService service;

    public DeploymentController(DeploymentService service) { this.service = service; }

    @PostMapping("/targets/{targetId}/deployment-profiles")
    public ResponseEntity<Map<String, DeploymentProfileView>> createProfile(
            @PathVariable UUID targetId, @Valid @RequestBody CreateProfileRequest request) {
        var view = service.createProfile(targetId, new CreateDeploymentProfileCommand(
                request.name(), request.serverUrl(), request.jobName(), request.credentialRef(),
                request.buildConfigDigest(), request.maxConcurrency(), request.ttlSeconds()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", view));
    }

    @GetMapping("/targets/{targetId}/deployment-profiles")
    public Map<String, List<DeploymentProfileView>> profiles(@PathVariable UUID targetId) {
        return Map.of("data", service.listProfiles(targetId));
    }

    @PostMapping("/targets/{targetId}/deployments")
    public ResponseEntity<Map<String, TargetDeploymentView>> prepare(
            @PathVariable UUID targetId, @Valid @RequestBody PrepareDeploymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data",
                service.prepare(targetId, request.profileId(), request.resolvedCommit())));
    }

    @GetMapping("/targets/{targetId}/deployments")
    public Map<String, List<TargetDeploymentView>> deployments(@PathVariable UUID targetId) {
        return Map.of("data", service.list(targetId));
    }

    @GetMapping("/deployments/{deploymentId}")
    public Map<String, TargetDeploymentView> deployment(@PathVariable UUID deploymentId) {
        return Map.of("data", service.get(deploymentId));
    }

    @PostMapping("/deployments/{deploymentId}/callbacks/jenkins")
    public Map<String, TargetDeploymentView> callback(
            @PathVariable UUID deploymentId, @Valid @RequestBody JenkinsCallbackRequest request) {
        return Map.of("data", service.callback(deploymentId, new DeploymentCallbackCommand(
                request.callbackKey(), request.providerRunId(), request.status(), request.endpoint(),
                request.artifactUri(), request.artifactDigest(), request.summary())));
    }

    public record CreateProfileRequest(
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 2048) String serverUrl,
            @NotBlank @Size(max = 512) String jobName,
            @Size(max = 128) String credentialRef,
            @NotBlank @Pattern(regexp = "^sha256:[0-9a-f]{64}$") String buildConfigDigest,
            @Min(1) @Max(50) int maxConcurrency,
            @Min(60) @Max(604800) int ttlSeconds
    ) { }

    public record PrepareDeploymentRequest(@NotNull UUID profileId,
                                           @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{40}$") String resolvedCommit) { }

    public record JenkinsCallbackRequest(
            @NotNull UUID callbackKey,
            @NotBlank @Size(max = 2048) String providerRunId,
            @NotNull DeploymentState status,
            @Size(max = 2048) String endpoint,
            @Size(max = 2048) String artifactUri,
            @Size(max = 255) String artifactDigest,
            @Size(max = 1000) String summary
    ) { }
}
