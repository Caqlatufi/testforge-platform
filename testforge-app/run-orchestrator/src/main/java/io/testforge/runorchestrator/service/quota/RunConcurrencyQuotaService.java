package io.testforge.runorchestrator.service.quota;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.runorchestrator.port.dispatch.TaskDispatchRegistration;
import io.testforge.runorchestrator.port.dispatch.TaskDispatchRegistrationPort;
import io.testforge.runorchestrator.run.entity.TestRunEntity;
import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.run.service.RunNotFoundException;
import io.testforge.runorchestrator.service.scheduling.RunSchedulingAggregation;
import io.testforge.runorchestrator.task.dag.release.DagReleaseService;
import io.testforge.runorchestrator.task.entity.TestTaskEntity;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.model.ResourceMode;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 以持久化 Task 状态作为配额真相源。Run 行锁将“核算容量 + 占用/释放”串成一个事务，
 * 因此多个调度线程领取不同 Task 时也不会突破 maxConcurrency。
 */
public class RunConcurrencyQuotaService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE = new TypeReference<>() { };

    private static final Set<TaskState> OCCUPYING_STATES = Set.copyOf(EnumSet.of(
            TaskState.DISPATCHED,
            TaskState.RUNNING
    ));
    private static final Set<TaskState> RELEASING_STATES = Set.copyOf(EnumSet.of(
            TaskState.SUCCEEDED,
            TaskState.FAILED,
            TaskState.TIMEOUT,
            TaskState.CANCELLED
    ));

    private final RunQuotaRepository runRepository;
    private final TaskQuotaRepository taskRepository;
    private final RunSchedulingAggregation aggregation;
    private final DagReleaseService dagReleaseService;
    private final TaskDispatchRegistrationPort dispatchRegistrationPort;
    private final ObjectMapper objectMapper;

    public RunConcurrencyQuotaService(
            RunQuotaRepository runRepository,
            TaskQuotaRepository taskRepository,
            DagReleaseService dagReleaseService
    ) {
        this(runRepository, taskRepository, new RunSchedulingAggregation(), dagReleaseService,
                TaskDispatchRegistrationPort.noop(), new ObjectMapper().findAndRegisterModules());
    }

    public RunConcurrencyQuotaService(
            RunQuotaRepository runRepository,
            TaskQuotaRepository taskRepository,
            DagReleaseService dagReleaseService,
            TaskDispatchRegistrationPort dispatchRegistrationPort,
            ObjectMapper objectMapper
    ) {
        this(runRepository, taskRepository, new RunSchedulingAggregation(), dagReleaseService,
                dispatchRegistrationPort, objectMapper);
    }

    RunConcurrencyQuotaService(
            RunQuotaRepository runRepository,
            TaskQuotaRepository taskRepository,
            RunSchedulingAggregation aggregation,
            DagReleaseService dagReleaseService,
            TaskDispatchRegistrationPort dispatchRegistrationPort,
            ObjectMapper objectMapper
    ) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.aggregation = Objects.requireNonNull(aggregation, "aggregation must not be null");
        this.dagReleaseService = Objects.requireNonNull(
                dagReleaseService,
                "dagReleaseService must not be null"
        );
        this.dispatchRegistrationPort = Objects.requireNonNull(
                dispatchRegistrationPort,
                "dispatchRegistrationPort must not be null"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Transactional
    public QuotaReservation acquire(UUID runId, UUID taskId, Instant scheduledAt) {
        requireIdentifiersAndTime(runId, taskId, scheduledAt);
        TestRunEntity run = lockRun(runId);
        TestTaskEntity task = lockOwnedTask(runId, taskId);

        if (OCCUPYING_STATES.contains(task.getState())) {
            return reservation(run, task, QuotaReservation.Outcome.ALREADY_ACQUIRED);
        }
        if (task.getState() != TaskState.QUEUED) {
            return reservation(run, task, QuotaReservation.Outcome.TASK_NOT_QUEUED);
        }
        if (task.getRetryAvailableAt() != null && task.getRetryAvailableAt().isAfter(scheduledAt)) {
            return reservation(run, task, QuotaReservation.Outcome.RETRY_NOT_READY);
        }
        if (!isSchedulable(run)) {
            return reservation(run, task, QuotaReservation.Outcome.RUN_NOT_SCHEDULABLE);
        }

        ResourceMode resourceMode = task.getResourceMode();
        int occupied = occupiedSlots(runId, resourceMode);
        int limit = limit(run, resourceMode);
        if (occupied >= limit) {
            task.markSchedulingWait(
                    resourceMode == ResourceMode.EXCLUSIVE_DEVICE
                            ? "DEVICE_POOL_LIMIT_REACHED"
                            : "PROCESS_POOL_LIMIT_REACHED",
                    scheduledAt
            );
            taskRepository.saveAndFlush(task);
            return new QuotaReservation(
                    runId,
                    taskId,
                    QuotaReservation.Outcome.LIMIT_REACHED,
                    occupied,
                    limit
            );
        }

        task.transitionTo(TaskState.DISPATCHED, scheduledAt);
        taskRepository.saveAndFlush(task);
        if (run.getState() == RunState.QUEUED) {
            run.applyAggregateState(RunState.RUNNING, scheduledAt);
            runRepository.saveAndFlush(run);
        }
        return reservation(run, task, QuotaReservation.Outcome.ACQUIRED);
    }

    @Transactional
    public void markWaiting(UUID runId, UUID taskId, String reason, Instant observedAt) {
        requireIdentifiersAndTime(runId, taskId, observedAt);
        lockRun(runId);
        TestTaskEntity task = lockOwnedTask(runId, taskId);
        if (task.getState() == TaskState.QUEUED) {
            task.markSchedulingWait(reason, observedAt);
            taskRepository.saveAndFlush(task);
        }
    }

    @Transactional
    public QuotaRelease release(
            UUID runId,
            UUID taskId,
            TaskState terminalState,
            Instant releasedAt
    ) {
        requireIdentifiersAndTime(runId, taskId, releasedAt);
        if (terminalState == null || !RELEASING_STATES.contains(terminalState)) {
            throw new IllegalArgumentException("释放配额必须提供 Task 终态");
        }

        TestRunEntity run = lockRun(runId);
        TestTaskEntity task = lockOwnedTask(runId, taskId);
        TaskState effectiveState = run.getCancellationRequestedAt() == null
                ? terminalState
                : TaskState.CANCELLED;

        QuotaRelease.Outcome outcome;
        if (task.getState() == effectiveState) {
            outcome = QuotaRelease.Outcome.ALREADY_RELEASED;
        } else {
            if (!OCCUPYING_STATES.contains(task.getState())) {
                throw new IllegalStateException("只有已占用配额的 Task 可以释放: " + task.getState());
            }
            task.transitionTo(effectiveState, releasedAt);
            taskRepository.saveAndFlush(task);
            outcome = QuotaRelease.Outcome.RELEASED;
        }

        var released = dagReleaseService.releaseSuccessors(runId, taskId);
        for (UUID releasedTaskId : released.releasedTaskIds()) {
            TestTaskEntity releasedTask = taskRepository.findById(releasedTaskId)
                    .orElseThrow(() -> new IllegalStateException(
                            "DAG 已释放但 Task 不存在: " + releasedTaskId
                    ));
            dispatchRegistrationPort.register(dispatchRegistration(run, releasedTask));
        }

        List<TestTaskEntity> tasks = taskRepository.findAllByRunIdOrderBySequenceNoAsc(runId);
        RunState aggregateState = aggregation.determine(run, tasks);
        run.applyAggregateState(aggregateState, releasedAt);
        runRepository.saveAndFlush(run);
        return new QuotaRelease(
                runId,
                taskId,
                outcome,
                task.getState(),
                run.getState(),
                occupiedSlots(runId),
                run.getMaxConcurrency()
        );
    }

    private boolean isSchedulable(TestRunEntity run) {
        return run.getCancellationRequestedAt() == null
                && (run.getState() == RunState.QUEUED || run.getState() == RunState.RUNNING);
    }

    private TestRunEntity lockRun(UUID runId) {
        return runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new RunNotFoundException("Run 不存在: " + runId));
    }

    private TestTaskEntity lockOwnedTask(UUID runId, UUID taskId) {
        TestTaskEntity task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task 不存在: " + taskId));
        if (!runId.equals(task.getRunId())) {
            throw new IllegalArgumentException("Task 不属于指定 Run: " + taskId);
        }
        return task;
    }

    private QuotaReservation reservation(
            TestRunEntity run,
            TestTaskEntity task,
            QuotaReservation.Outcome outcome
    ) {
        return new QuotaReservation(
                run.getId(),
                task.getId(),
                outcome,
                occupiedSlots(run.getId(), task.getResourceMode()),
                limit(run, task.getResourceMode())
        );
    }

    private TaskDispatchRegistration dispatchRegistration(
            TestRunEntity run,
            TestTaskEntity task
    ) {
        return new TaskDispatchRegistration(
                task.getId(),
                task.getRunId(),
                task.getRunner(),
                task.getResourceMode(),
                task.getPlatform(),
                task.getSourceRef(),
                task.getScriptChecksum(),
                task.getTimeoutSeconds(),
                read(task.getRequiredFeatures(), STRING_LIST_TYPE),
                read(task.getParametersJson(), OBJECT_MAP_TYPE),
                run.getPriority(),
                1,
                task.getUpdatedAt()
        );
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Task 持久化 JSON 无法解析", exception);
        }
    }

    private int limit(TestRunEntity run, ResourceMode mode) {
        return mode == ResourceMode.EXCLUSIVE_DEVICE
                ? run.getDeviceConcurrency()
                : run.getProcessConcurrency();
    }

    private int occupiedSlots(UUID runId) {
        return Math.toIntExact(taskRepository.countByRunIdAndStateIn(runId, OCCUPYING_STATES));
    }

    private int occupiedSlots(UUID runId, ResourceMode mode) {
        return Math.toIntExact(taskRepository.countByRunIdAndResourceModeAndStateIn(
                runId, mode, OCCUPYING_STATES
        ));
    }

    private void requireIdentifiersAndTime(UUID runId, UUID taskId, Instant at) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(at, "at must not be null");
    }
}
