package io.testforge.app.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.testforge.dispatcher.stream.DispatchTelemetry;
import io.testforge.runorchestrator.run.service.RunTaskService;
import io.testforge.runorchestrator.task.model.ResourceMode;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.workergateway.device.lease.DeviceLeaseService;
import io.testforge.workergateway.device.lease.DeviceLeaseState;
import io.testforge.workergateway.device.registry.model.DeviceSlotStatus;
import io.testforge.workergateway.device.registry.service.DeviceRegistryService;
import io.testforge.workergateway.registry.model.WorkerStatus;
import io.testforge.workergateway.registry.service.WorkerRegistryService;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PlatformMonitoringService {
    private static final Set<TaskState> RUNNING = EnumSet.of(TaskState.DISPATCHED, TaskState.RUNNING);
    private final RunTaskService runs;
    private final WorkerRegistryService workers;
    private final DeviceRegistryService devices;
    private final DeviceLeaseService leases;
    private final DispatchTelemetry dispatch;

    public PlatformMonitoringService(
            RunTaskService runs,
            WorkerRegistryService workers,
            DeviceRegistryService devices,
            DeviceLeaseService leases,
            DispatchTelemetry dispatch,
            MeterRegistry registry
    ) {
        this.runs = runs;
        this.workers = workers;
        this.devices = devices;
        this.leases = leases;
        this.dispatch = dispatch;
        registry.gauge("testforge_tasks_queued", Tags.of("resource_mode", "PROCESS_POOL"), this,
                ignored -> snapshot().processQueued());
        registry.gauge("testforge_tasks_queued", Tags.of("resource_mode", "EXCLUSIVE_DEVICE"), this,
                ignored -> snapshot().deviceQueued());
        registry.gauge("testforge_tasks_running", Tags.of("resource_mode", "PROCESS_POOL"), this,
                ignored -> snapshot().processRunning());
        registry.gauge("testforge_tasks_running", Tags.of("resource_mode", "EXCLUSIVE_DEVICE"), this,
                ignored -> snapshot().deviceRunning());
        registry.gauge("testforge_resource_capacity", Tags.of("resource_mode", "PROCESS_POOL"), this,
                ignored -> snapshot().processCapacity());
        registry.gauge("testforge_resource_capacity", Tags.of("resource_mode", "EXCLUSIVE_DEVICE"), this,
                ignored -> snapshot().deviceCapacity());
        registry.gauge("testforge_dispatch_redeliveries", this,
                ignored -> snapshot().dispatch().redeliveries());
        registry.gauge("testforge_dispatch_redis_unavailable", this,
                ignored -> snapshot().dispatch().redisUnavailable());
    }

    public PlatformResourceSnapshot snapshot() {
        var tasks = runs.listRuns().stream()
                .filter(run -> run.testJobId() != null)
                .flatMap(run -> run.tasks().stream()).toList();
        var onlineWorkers = workers.list(WorkerStatus.ONLINE);
        var onlineDevices = devices.list(DeviceSlotStatus.AVAILABLE);
        long deviceAvailable = onlineDevices.stream()
                .filter(device -> available(device.slotId()))
                .count();
        Map<String, Long> executorCapacity = new LinkedHashMap<>();
        onlineWorkers.forEach(worker -> worker.capabilities().stream()
                .filter(capability -> capability.startsWith("RUNNER_"))
                .map(capability -> capability.substring("RUNNER_".length()).toLowerCase(Locale.ROOT).replace('_', '-'))
                .forEach(runner -> executorCapacity.merge(runner, (long) worker.maxConcurrency(), Long::sum)));
        long processCapacity = onlineWorkers.stream()
                .filter(worker -> worker.capabilities().stream().anyMatch(capability ->
                        capability.startsWith("RUNNER_") && !"RUNNER_AIRTEST".equals(capability)))
                .mapToLong(worker -> worker.maxConcurrency()).sum();
        return new PlatformResourceSnapshot(
                tasks.stream().filter(task -> task.state() == TaskState.WAITING_DEPLOYMENT).count(),
                count(tasks, ResourceMode.PROCESS_POOL, Set.of(TaskState.QUEUED)),
                count(tasks, ResourceMode.PROCESS_POOL, RUNNING),
                processCapacity,
                count(tasks, ResourceMode.EXCLUSIVE_DEVICE, Set.of(TaskState.QUEUED)),
                count(tasks, ResourceMode.EXCLUSIVE_DEVICE, RUNNING),
                onlineDevices.size(),
                deviceAvailable,
                onlineWorkers.size(),
                executorCapacity,
                dispatch.snapshot()
        );
    }

    private boolean available(java.util.UUID slotId) {
        try {
            return leases.load(slotId).state() == DeviceLeaseState.AVAILABLE;
        } catch (RuntimeException missingLease) {
            return false;
        }
    }

    private long count(
            java.util.List<io.testforge.runorchestrator.task.model.TaskView> tasks,
            ResourceMode mode,
            Set<TaskState> states
    ) {
        return tasks.stream().filter(task -> task.resourceMode() == mode && states.contains(task.state())).count();
    }
}
