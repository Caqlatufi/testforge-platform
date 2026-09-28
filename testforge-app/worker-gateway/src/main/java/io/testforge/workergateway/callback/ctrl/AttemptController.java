package io.testforge.workergateway.callback.ctrl;

import io.testforge.workergateway.artifact.model.ArtifactUploadPolicyRequest;
import io.testforge.workergateway.artifact.model.ArtifactUploadPolicyResponse;
import io.testforge.workergateway.artifact.service.ArtifactUploadPolicyService;
import io.testforge.workergateway.artifact.service.ArtifactUploadUnavailableException;
import io.testforge.workergateway.callback.model.AttemptCallbackRequest;
import io.testforge.workergateway.callback.model.AttemptHeartbeatRequest;
import io.testforge.workergateway.callback.model.AttemptLeaseResponse;
import io.testforge.workergateway.callback.model.AttemptStartRequest;
import io.testforge.workergateway.callback.model.CallbackReceiptResponse;
import io.testforge.workergateway.callback.service.AttemptCallbackService;
import io.testforge.workergateway.callback.service.AttemptLifecycleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attempts")
public class AttemptController {

    private final AttemptLifecycleService lifecycleService;
    private final AttemptCallbackService callbackService;
    private final ArtifactUploadPolicyService artifactUploadPolicyService;
    private final Clock receiptClock;

    public AttemptController(
            AttemptLifecycleService lifecycleService,
            AttemptCallbackService callbackService
    ) {
        this(lifecycleService, callbackService, null, Clock.systemUTC());
    }

    public AttemptController(
            AttemptLifecycleService lifecycleService,
            AttemptCallbackService callbackService,
            ArtifactUploadPolicyService artifactUploadPolicyService
    ) {
        this(lifecycleService, callbackService, artifactUploadPolicyService, Clock.systemUTC());
    }

    AttemptController(
            AttemptLifecycleService lifecycleService,
            AttemptCallbackService callbackService,
            Clock receiptClock
    ) {
        this(lifecycleService, callbackService, null, receiptClock);
    }

    AttemptController(
            AttemptLifecycleService lifecycleService,
            AttemptCallbackService callbackService,
            ArtifactUploadPolicyService artifactUploadPolicyService,
            Clock receiptClock
    ) {
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService 不能为空");
        this.callbackService = Objects.requireNonNull(callbackService, "callbackService 不能为空");
        this.artifactUploadPolicyService = artifactUploadPolicyService;
        this.receiptClock = Objects.requireNonNull(receiptClock, "receiptClock 不能为空");
    }

    @PostMapping("/{attemptId}/artifacts")
    public ApiResponse<ArtifactUploadPolicyResponse> artifactUploadPolicy(
            @PathVariable UUID attemptId,
            @Valid @RequestBody ArtifactUploadPolicyRequest request
    ) {
        if (artifactUploadPolicyService == null) {
            throw new ArtifactUploadUnavailableException("OSS 附件上传策略未启用");
        }
        return ApiResponse.success(artifactUploadPolicyService.create(attemptId, request));
    }

    @PostMapping("/{attemptId}/start")
    public ApiResponse<AttemptLeaseResponse> start(
            @PathVariable UUID attemptId,
            @Valid @RequestBody AttemptStartRequest request
    ) {
        return ApiResponse.success(lifecycleService.start(attemptId, request));
    }

    @PostMapping("/{attemptId}/heartbeat")
    public ApiResponse<AttemptLeaseResponse> heartbeat(
            @PathVariable UUID attemptId,
            @Valid @RequestBody AttemptHeartbeatRequest request
    ) {
        return ApiResponse.success(lifecycleService.heartbeat(attemptId, request));
    }

    @PostMapping("/{attemptId}/callback")
    public ApiResponse<CallbackReceiptResponse> callback(
            @PathVariable UUID attemptId,
            @Valid @RequestBody AttemptCallbackRequest request
    ) {
        return ApiResponse.success(callbackService.callback(
                attemptId, request, receiptClock.instant()
        ));
    }
}
