package io.testforge.workergateway.adapter.inbound;

import io.testforge.casecatalog.workflow.compile.model.PublishNodeType;
import io.testforge.runorchestrator.run.model.TaskExecutionView;
import io.testforge.runorchestrator.run.service.RunTaskService;
import io.testforge.runorchestrator.service.quota.QuotaReservation;
import io.testforge.runorchestrator.service.quota.RunConcurrencyQuotaService;
import io.testforge.runorchestrator.service.reliability.ExecutionReliabilityService;
import io.testforge.runorchestrator.task.model.TaskView;
import io.testforge.runorchestrator.task.model.ResourceMode;
import io.testforge.workergateway.device.lease.DeviceLeaseService;
import io.testforge.workergateway.device.registry.service.DeviceRegistryService;
import io.testforge.workergateway.registry.model.WorkerNodeView;
import io.testforge.workergateway.registry.service.WorkerRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskClaimServiceTest {

    private final RunTaskService runTaskService = mock(RunTaskService.class);
    private final RunConcurrencyQuotaService quotaService = mock(RunConcurrencyQuotaService.class);
    private final ExecutionReliabilityService reliabilityService = mock(ExecutionReliabilityService.class);
    private final WorkerRegistryService workerRegistryService = mock(WorkerRegistryService.class);
    private final DeviceRegistryService deviceRegistryService = mock(DeviceRegistryService.class);
    private final DeviceLeaseService deviceLeaseService = mock(DeviceLeaseService.class);
    private final UUID runId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final TaskView task = mock(TaskView.class);
    private TaskClaimService service;

    @BeforeEach
    void setUp() {
        TaskExecutionView execution = mock(TaskExecutionView.class);
        WorkerNodeView worker = mock(WorkerNodeView.class);
        when(execution.task()).thenReturn(task);
        when(task.platform()).thenReturn("WINDOWS");
        when(task.requiredFeatures()).thenReturn(List.of("WINDOWS_UI"));
        when(task.runner()).thenReturn("pytest-http");
        when(runTaskService.getTaskExecution(runId, taskId)).thenReturn(execution);
        when(worker.workerId()).thenReturn("worker-1");
        when(workerRegistryService.findMatching(any())).thenReturn(List.of(worker));
        service = new TaskClaimService(
                runTaskService,
                quotaService,
                reliabilityService,
                workerRegistryService,
                deviceRegistryService,
                deviceLeaseService,
                Duration.ofSeconds(30),
                "http://127.0.0.1:8081"
        );
    }

    @Test
    void concurrencyLimitIsReportedAsRetryableLocked() {
        when(quotaService.acquire(any(), any(), any(Instant.class))).thenReturn(reservation(
                QuotaReservation.Outcome.LIMIT_REACHED
        ));

        assertStatus(HttpStatus.LOCKED);
    }

    @Test
    void staleTaskIsReportedAsTerminalConflict() {
        when(quotaService.acquire(any(), any(), any(Instant.class))).thenReturn(reservation(
                QuotaReservation.Outcome.TASK_NOT_QUEUED
        ));

        assertStatus(HttpStatus.CONFLICT);
    }

    @Test
    void busyDeviceIsReportedAsRetryableLocked() {
        when(task.runner()).thenReturn("airtest");
        when(quotaService.acquire(any(), any(), any(Instant.class))).thenReturn(reservation(
                QuotaReservation.Outcome.ACQUIRED
        ));
        when(deviceRegistryService.findMatching(any())).thenReturn(List.of());

        assertStatus(HttpStatus.LOCKED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void automaticMachineAllocationAllowsNullRunEnvironmentInClaimEnvelope() {
        when(task.id()).thenReturn(taskId);
        when(task.runId()).thenReturn(runId);
        when(task.workflowNodeId()).thenReturn(UUID.randomUUID());
        when(task.sourceType()).thenReturn(PublishNodeType.CASE);
        when(task.sourceRef()).thenReturn("C:/tests/demo.air");
        when(task.scriptChecksum()).thenReturn("sha256:" + "a".repeat(64));
        when(task.parameters()).thenReturn(Map.of());
        when(task.resourceMode()).thenReturn(ResourceMode.PROCESS_POOL);
        when(runTaskService.getTaskExecution(runId, taskId)).thenReturn(new TaskExecutionView(
                UUID.randomUUID(), UUID.randomUUID(), null, null, Map.of(), Map.of(), task));
        when(quotaService.acquire(any(), any(), any(Instant.class))).thenReturn(reservation(
                QuotaReservation.Outcome.ACQUIRED));
        when(reliabilityService.createAttempt(any(), any(Instant.class))).thenReturn(true);

        Map<String, Object> claimed = service.claim(runId, taskId, UUID.randomUUID(), "worker-1");
        Map<String, Object> execution = (Map<String, Object>) claimed.get("execution");
        Map<String, Object> environment = (Map<String, Object>) execution.get("environment");

        assertThat(environment).containsKeys("environmentId", "endpoint", "config", "secretRefs");
        assertThat(environment.get("environmentId")).isNull();
        assertThat(environment.get("endpoint")).isNull();
    }

    private QuotaReservation reservation(QuotaReservation.Outcome outcome) {
        return new QuotaReservation(runId, taskId, outcome, 0, 1);
    }

    private void assertStatus(HttpStatus expected) {
        assertThatThrownBy(() -> service.claim(runId, taskId, UUID.randomUUID(), "worker-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(expected));
    }
}
