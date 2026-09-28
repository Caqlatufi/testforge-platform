package io.testforge.workergateway.adapter.inbound;

import io.testforge.runorchestrator.run.model.TaskExecutionView;
import io.testforge.runorchestrator.run.service.RunTaskService;
import io.testforge.runorchestrator.service.quota.QuotaReservation;
import io.testforge.runorchestrator.service.quota.RunConcurrencyQuotaService;
import io.testforge.runorchestrator.service.reliability.AttemptLeaseRecord;
import io.testforge.runorchestrator.service.reliability.ExecutionReliabilityService;
import io.testforge.runorchestrator.task.model.ResourceMode;
import io.testforge.common.event.ExecutionEventCommand;
import io.testforge.common.event.ExecutionEventPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import io.testforge.workergateway.device.lease.DeviceLeaseService;
import io.testforge.workergateway.device.registry.model.DeviceRequirement;
import io.testforge.workergateway.device.registry.model.DeviceSlotView;
import io.testforge.workergateway.device.registry.service.DeviceRegistryService;
import io.testforge.workergateway.registry.model.WorkerRequirement;
import io.testforge.workergateway.registry.service.WorkerRegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 把 Redis 的 TASK_READY 通知原子升级为 Worker 可执行的 Attempt 信封。 */
@Service
@ConditionalOnBean({RunTaskService.class, ExecutionReliabilityService.class})
public class TaskClaimService {

    private final RunTaskService runTaskService;
    private final RunConcurrencyQuotaService quotaService;
    private final ExecutionReliabilityService reliabilityService;
    private final WorkerRegistryService workerRegistryService;
    private final DeviceRegistryService deviceRegistryService;
    private final DeviceLeaseService deviceLeaseService;
    private final Clock clock;
    private final Duration leaseDuration;
    private final String publicBaseUrl;
    private final ExecutionEventPort executionEventPort;

    @Autowired
    public TaskClaimService(
            RunTaskService runTaskService,
            RunConcurrencyQuotaService quotaService,
            ExecutionReliabilityService reliabilityService,
            WorkerRegistryService workerRegistryService,
            DeviceRegistryService deviceRegistryService,
            DeviceLeaseService deviceLeaseService,
            ObjectProvider<ExecutionEventPort> eventPortProvider,
            @Value("${testforge.worker-gateway.attempt-lease-duration:30s}") Duration leaseDuration,
            @Value("${testforge.public-base-url:http://127.0.0.1:${server.port:8081}}") String publicBaseUrl
    ) {
        this.runTaskService = runTaskService;
        this.quotaService = quotaService;
        this.reliabilityService = reliabilityService;
        this.workerRegistryService = workerRegistryService;
        this.deviceRegistryService = deviceRegistryService;
        this.deviceLeaseService = deviceLeaseService;
        this.clock = Clock.systemUTC();
        this.leaseDuration = leaseDuration;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.executionEventPort = eventPortProvider.getIfAvailable(ExecutionEventPort::noop);
    }

    public TaskClaimService(
            RunTaskService runTaskService,
            RunConcurrencyQuotaService quotaService,
            ExecutionReliabilityService reliabilityService,
            WorkerRegistryService workerRegistryService,
            DeviceRegistryService deviceRegistryService,
            DeviceLeaseService deviceLeaseService,
            Duration leaseDuration,
            String publicBaseUrl
    ) {
        this.runTaskService = runTaskService;
        this.quotaService = quotaService;
        this.reliabilityService = reliabilityService;
        this.workerRegistryService = workerRegistryService;
        this.deviceRegistryService = deviceRegistryService;
        this.deviceLeaseService = deviceLeaseService;
        this.clock = Clock.systemUTC();
        this.leaseDuration = leaseDuration;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.executionEventPort = ExecutionEventPort.noop();
    }

    @Transactional
    public Map<String, Object> claim(UUID runId, UUID taskId, UUID messageId, String workerId) {
        TaskExecutionView execution = runTaskService.getTaskExecution(runId, taskId);
        String platform = execution.task().platform() == null ? "ANY" : execution.task().platform();
        Set<String> required = Set.copyOf(execution.task().requiredFeatures());
        boolean workerMatches = workerRegistryService.findMatching(new WorkerRequirement(
                "1.0", execution.task().runner(), platform, required
        )).stream().anyMatch(worker -> worker.workerId().equals(workerId));
        if (!workerMatches) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Worker 能力或在线状态不匹配");
        }

        Instant now = clock.instant();
        UUID attemptId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        ResourceMode resourceMode = execution.task().resourceMode() == null
                ? ResourceMode.fromRunner(execution.task().runner())
                : execution.task().resourceMode();
        DeviceSlotView device = null;
        if (resourceMode == ResourceMode.EXCLUSIVE_DEVICE) {
            try {
                device = reserveDevice(workerId, platform, required, attemptId);
            } catch (ResponseStatusException unavailable) {
                quotaService.markWaiting(runId, taskId, "NO_MATCHING_DEVICE", now);
                throw unavailable;
            }
        }

        QuotaReservation reservation = quotaService.acquire(runId, taskId, now);
        if (reservation.outcome() != QuotaReservation.Outcome.ACQUIRED) {
            if (device != null) {
                deviceLeaseService.releaseTerminal(attemptId);
            }
            boolean retryable = reservation.outcome() == QuotaReservation.Outcome.LIMIT_REACHED
                    || reservation.outcome() == QuotaReservation.Outcome.RETRY_NOT_READY;
            throw new ResponseStatusException(retryable ? HttpStatus.LOCKED : HttpStatus.CONFLICT,
                    "Task 无法领取: " + reservation.outcome());
        }

        boolean created = reliabilityService.createAttempt(new AttemptLeaseRecord(
                attemptId, taskId, workerId, leaseToken, now.plus(leaseDuration), 0
        ), now);
        if (!created) {
            if (device != null) {
                deviceLeaseService.releaseTerminal(attemptId);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Task 已被其他 Worker 领取");
        }
        executionEventPort.append(new ExecutionEventCommand(
                UUID.nameUUIDFromBytes((attemptId + "|ATTEMPT_CREATED").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                runId, taskId, attemptId, "ATTEMPT_CREATED", now,
                Map.of(
                        "workerId", workerId,
                        "resourceMode", resourceMode.name(),
                        "deviceSlotId", device == null ? "" : device.slotId().toString()
                )
        ));
        return envelope(messageId, execution, attemptId, leaseToken, workerId, platform, device, now);
    }

    private DeviceSlotView reserveDevice(
            String workerId, String platform, Set<String> required, UUID attemptId
    ) {
        List<DeviceSlotView> candidates = deviceRegistryService.findMatching(
                new DeviceRequirement(platform, required)
        ).stream().filter(slot -> slot.workerId().equals(workerId)).toList();
        for (DeviceSlotView candidate : candidates) {
            try {
                deviceLeaseService.reserve(candidate.slotId(), attemptId);
                return candidate;
            } catch (RuntimeException ignored) {
                // 并发保留失败时继续尝试同 Worker 的下一个匹配设备。
            }
        }
        throw new ResponseStatusException(HttpStatus.LOCKED, "没有可独占的匹配 DeviceSlot");
    }

    private Map<String, Object> envelope(
            UUID messageId,
            TaskExecutionView context,
            UUID attemptId,
            UUID leaseToken,
            String workerId,
            String platform,
            DeviceSlotView device,
            Instant now
    ) {
        var task = context.task();
        Map<String, Object> parameters = new LinkedHashMap<>(task.parameters());
        if (device != null) {
            parameters.put("deviceUri", device.deviceUri());
            parameters.put("deviceId", device.deviceId());
        }
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("projectId", context.projectId());
        execution.put("targetId", context.targetId());
        execution.put("workflowNodeId", task.workflowNodeId());
        execution.put("sourceType", task.sourceType().name().equals("FIXTURE") ? "FIXTURE" : "ASSERTION");
        execution.put("sourceRef", task.sourceRef());
        execution.put("runner", task.runner());
        execution.put("platform", platform);
        execution.put("requiredFeatures", new ArrayList<>(task.requiredFeatures()));
        execution.put("resourceMode", task.resourceMode().name());
        if (task.targetRevision() != null) {
            Map<String, Object> targetRevision = new LinkedHashMap<>();
            targetRevision.put("repositoryUrl", task.targetRevision().repositoryUrl());
            targetRevision.put("requestedType", task.targetRevision().requestedType().name());
            targetRevision.put("requestedValue", task.targetRevision().requestedValue());
            targetRevision.put("resolvedCommit", task.targetRevision().resolvedCommit());
            targetRevision.put("resolvedAt", task.targetRevision().resolvedAt().toString());
            execution.put("targetRevision", targetRevision);
        }
        execution.put("script", Map.of(
                "version", task.scriptVersion(),
                "sourceRef", task.sourceRef(),
                "checksum", task.scriptChecksum()
        ));
        execution.put("parameters", parameters);
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("environmentId", context.environmentId());
        environment.put("endpoint", context.environmentEndpoint());
        environment.put("config", context.environmentConfig());
        environment.put("secretRefs", context.secretRefs());
        execution.put("environment", environment);
        execution.put("timeoutSeconds", task.timeoutSeconds());
        if (device != null) {
            execution.put("deviceSlotId", device.slotId());
        }
        String attemptPath = "/api/v1/attempts/" + attemptId;
        Map<String, Object> callbacks = Map.of(
                "startUrl", publicBaseUrl + attemptPath + "/start",
                "heartbeatUrl", publicBaseUrl + attemptPath + "/heartbeat",
                "completeUrl", publicBaseUrl + attemptPath + "/callback",
                "artifactUploadUrl", publicBaseUrl + attemptPath + "/artifacts"
        );
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "1.0.0");
        result.put("messageId", messageId.toString());
        result.put("publishedAt", now.toString());
        result.put("deliveryAttempt", 1);
        result.put("taskId", task.id().toString());
        result.put("runId", task.runId().toString());
        result.put("attemptId", attemptId.toString());
        result.put("leaseToken", leaseToken.toString());
        result.put("workerProtocol", "1.0");
        result.put("execution", execution);
        result.put("callbacks", callbacks);
        result.put("traceparent", "00-" + traceId + "-" + spanId + "-01");
        result.put("claimedBy", workerId);
        return result;
    }
}
