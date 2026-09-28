package io.testforge.runorchestrator.service.reliability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.runorchestrator.entity.attempt.TaskAttemptEntity;
import io.testforge.runorchestrator.model.attempt.AttemptState;
import io.testforge.runorchestrator.port.dispatch.TaskDispatchRegistration;
import io.testforge.runorchestrator.port.dispatch.TaskDispatchRegistrationPort;
import io.testforge.runorchestrator.repo.attempt.TaskAttemptRepository;
import io.testforge.runorchestrator.run.entity.TestRunEntity;
import io.testforge.runorchestrator.run.repo.TestRunRepository;
import io.testforge.runorchestrator.task.entity.TestTaskEntity;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.repo.TestTaskRepository;
import io.testforge.runorchestrator.task.service.TaskStateMachine;
import io.testforge.runorchestrator.service.quota.RunConcurrencyQuotaService;
import io.testforge.common.event.ExecutionEventCommand;
import io.testforge.common.event.ExecutionEventPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/**
 * dispatcher 的 MySQL 真相源边界：Attempt 租约与 Task/Attempt 双版本迁移都在这里落库。
 * dispatcher 只依赖此公开服务，不跨模块访问 Repository。
 */
public class ExecutionReliabilityService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE = new TypeReference<>() { };

    private final TestTaskRepository taskRepository;
    private final TaskAttemptRepository attemptRepository;
    private final TestRunRepository runRepository;
    private final TaskDispatchRegistrationPort dispatchRegistrationPort;
    private final ObjectMapper objectMapper;
    private final RunConcurrencyQuotaService quotaService;
    private final ExecutionEventPort executionEventPort;
    private final TaskStateMachine taskStateMachine = new TaskStateMachine();

    public ExecutionReliabilityService(
            TestTaskRepository taskRepository,
            TaskAttemptRepository attemptRepository,
            TestRunRepository runRepository,
            TaskDispatchRegistrationPort dispatchRegistrationPort,
            ObjectMapper objectMapper,
            RunConcurrencyQuotaService quotaService
    ) {
        this(taskRepository, attemptRepository, runRepository, dispatchRegistrationPort,
                objectMapper, quotaService, ExecutionEventPort.noop());
    }

    public ExecutionReliabilityService(
            TestTaskRepository taskRepository,
            TaskAttemptRepository attemptRepository,
            TestRunRepository runRepository,
            TaskDispatchRegistrationPort dispatchRegistrationPort,
            ObjectMapper objectMapper,
            RunConcurrencyQuotaService quotaService,
            ExecutionEventPort executionEventPort
    ) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository 不能为空");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository 不能为空");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository 不能为空");
        this.dispatchRegistrationPort = Objects.requireNonNull(
                dispatchRegistrationPort, "dispatchRegistrationPort 不能为空"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.quotaService = Objects.requireNonNull(quotaService, "quotaService 不能为空");
        this.executionEventPort = Objects.requireNonNull(executionEventPort, "executionEventPort 不能为空");
    }

    @Transactional
    public boolean createAttempt(AttemptLeaseRecord lease, Instant issuedAt) {
        Objects.requireNonNull(lease, "lease 不能为空");
        Objects.requireNonNull(issuedAt, "issuedAt 不能为空");
        TestTaskEntity task = taskRepository.findByIdForUpdate(lease.taskId()).orElse(null);
        if (task == null || task.getState() != TaskState.DISPATCHED
                || task.getCancellationRequestedAt() != null
                || attemptRepository.existsById(lease.attemptId())) {
            return false;
        }
        Optional<TaskAttemptEntity> latest = attemptRepository.findTopByTaskIdOrderByAttemptNoDesc(task.getId());
        if (latest.isPresent() && !latest.get().getState().isTerminal()) {
            return false;
        }
        int attemptNo = latest.map(value -> value.getAttemptNo() + 1).orElse(1);
        TaskAttemptEntity attempt = new TaskAttemptEntity(
                lease.attemptId(),
                lease.taskId(),
                attemptNo,
                lease.workerId(),
                lease.leaseToken(),
                lease.leaseUntil(),
                issuedAt
        );
        attempt.start(issuedAt);
        task.transitionTo(TaskState.RUNNING, issuedAt);
        attemptRepository.save(attempt);
        taskRepository.save(task);
        attemptRepository.flush();
        appendEvent(task, attempt.getId(), "ATTEMPT_STARTED", issuedAt,
                Map.of("attemptNo", attemptNo, "workerId", lease.workerId()));
        return true;
    }

    @Transactional
    public Optional<AttemptLeaseRecord> heartbeat(
            UUID attemptId,
            String workerId,
            UUID leaseToken,
            Instant acceptedAt,
            Instant extendedUntil
    ) {
        int changed = attemptRepository.heartbeat(
                attemptId, workerId, leaseToken, acceptedAt, extendedUntil
        );
        if (changed != 1) return Optional.empty();
        Optional<TaskAttemptEntity> renewed = attemptRepository.findById(attemptId);
        renewed.ifPresent(attempt -> taskRepository.findById(attempt.getTaskId()).ifPresent(task ->
                appendEvent(task, attemptId, "ATTEMPT_HEARTBEAT", acceptedAt,
                        Map.of("workerId", workerId, "leaseUntil", extendedUntil.toString()))));
        return renewed.map(this::toLease);
    }

    @Transactional(readOnly = true)
    public List<AttemptLeaseRecord> findExpired(Instant expiredAtOrBefore, int limit) {
        return attemptRepository.findExpiredRunning(expiredAtOrBefore, PageRequest.of(0, limit))
                .stream()
                .map(this::toLease)
                .toList();
    }

    @Transactional
    public boolean markLostIfExpired(AttemptLeaseRecord candidate, Instant detectedAt) {
        return attemptRepository.markLostIfExpired(
                candidate.attemptId(),
                candidate.leaseToken(),
                candidate.version(),
                detectedAt
        ) == 1;
    }

    @Transactional(readOnly = true)
    public ExecutionStateRecord load(UUID taskId) {
        TestTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task 不存在: " + taskId));
        TaskAttemptEntity attempt = attemptRepository.findTopByTaskIdOrderByAttemptNoDesc(taskId)
                .orElseThrow(() -> new IllegalStateException("Task 尚无 Attempt: " + taskId));
        return toExecutionState(task, attempt);
    }

    @Transactional
    public boolean compareAndSet(
            ExecutionStateRecord expected,
            ExecutionTransitionCommand transition
    ) {
        Objects.requireNonNull(expected, "expected 不能为空");
        Objects.requireNonNull(transition, "transition 不能为空");
        TestTaskEntity task = taskRepository.findById(expected.taskId()).orElse(null);
        TaskAttemptEntity attempt = attemptRepository.findById(expected.attemptId()).orElse(null);
        if (!matches(expected, task, attempt)) {
            return false;
        }

        taskStateMachine.requireTransition(expected.taskState(), transition.taskState());
        if (expected.attemptState() != transition.attemptState()) {
            int attemptChanged = attemptRepository.compareAndSetState(
                    expected.attemptId(),
                    expected.attemptState(),
                    expected.attemptVersion(),
                    transition.attemptState(),
                    transition.transitionedAt()
            );
            if (attemptChanged != 1) {
                throw new ReliabilityStateConflictException();
            }
        }

        Instant completedAt = transition.taskState().isTerminal() ? transition.transitionedAt() : null;
        int taskChanged = taskRepository.compareAndSetReliabilityState(
                expected.taskId(),
                expected.taskState(),
                expected.taskVersion(),
                transition.taskState(),
                transition.retryAt(),
                transition.transitionedAt(),
                completedAt
        );
        if (taskChanged != 1) {
            throw new ReliabilityStateConflictException();
        }
        if (transition.taskState() == TaskState.QUEUED) {
            registerRetry(task, attempt.getAttemptNo() + 1, transition.retryAt());
        } else if (transition.taskState().isTerminal()) {
            quotaService.release(
                    task.getRunId(),
                    task.getId(),
                    transition.taskState(),
                    transition.transitionedAt()
            );
        }
        appendEvent(task, attempt.getId(),
                transition.taskState() == TaskState.QUEUED ? "TASK_RETRY_QUEUED" : "TASK_STATE_CHANGED",
                transition.transitionedAt(),
                Map.of("taskState", transition.taskState().name(), "attemptState", transition.attemptState().name()));
        return true;
    }

    private void appendEvent(TestTaskEntity task, UUID attemptId, String type, Instant occurredAt, Map<String, Object> payload) {
        String identity = task.getId() + "|" + attemptId + "|" + type + "|" + occurredAt;
        executionEventPort.append(new ExecutionEventCommand(
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                task.getRunId(), task.getId(), attemptId, type, occurredAt, payload
        ));
    }

    @Transactional(readOnly = true)
    public List<RecoveryAttemptRecord> findRecoverableLost(int limit) {
        return recoverable(AttemptState.LOST, limit, null);
    }

    @Transactional(readOnly = true)
    public List<RecoveryAttemptRecord> findTimedOut(Instant now, int limit) {
        return recoverable(AttemptState.RUNNING, limit, now);
    }

    private List<RecoveryAttemptRecord> recoverable(AttemptState state, int limit, Instant timeoutCutoff) {
        List<RecoveryAttemptRecord> result = new ArrayList<>();
        for (TaskAttemptEntity attempt : attemptRepository.findRecoveryCandidates(
                state.name(),
                List.of(TaskState.RUNNING.name(), TaskState.DISPATCHED.name()),
                PageRequest.of(0, limit))) {
            TestTaskEntity task = taskRepository.findById(attempt.getTaskId()).orElse(null);
            if (task == null) {
                continue;
            }
            Instant occurredAt = attempt.getUpdatedAt();
            if (timeoutCutoff != null) {
                Instant deadline = attempt.getCreatedAt().plusSeconds(task.getTimeoutSeconds());
                if (deadline.isAfter(timeoutCutoff)) {
                    continue;
                }
                occurredAt = deadline;
            }
            result.add(new RecoveryAttemptRecord(
                    task.getId(), attempt.getId(), attempt.getAttemptNo(), occurredAt
            ));
            if (result.size() == limit) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private boolean matches(
            ExecutionStateRecord expected,
            TestTaskEntity task,
            TaskAttemptEntity attempt
    ) {
        return task != null
                && attempt != null
                && task.getPersistenceVersion() == expected.taskVersion()
                && task.getState() == expected.taskState()
                && attempt.getTaskId().equals(task.getId())
                && attempt.getAttemptNo() == expected.attemptNo()
                && attempt.getVersion() == expected.attemptVersion()
                && attempt.getState() == expected.attemptState()
                && Objects.equals(task.getCancellationRequestedAt() != null, expected.cancellationRequested());
    }

    private void registerRetry(TestTaskEntity task, int attemptNo, Instant retryAt) {
        TestRunEntity run = runRepository.findById(task.getRunId())
                .orElseThrow(() -> new IllegalStateException("Task 对应 Run 不存在: " + task.getRunId()));
        dispatchRegistrationPort.register(new TaskDispatchRegistration(
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
                attemptNo,
                retryAt
        ));
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Task 持久化 JSON 无法解析", exception);
        }
    }

    private AttemptLeaseRecord toLease(TaskAttemptEntity attempt) {
        return new AttemptLeaseRecord(
                attempt.getId(),
                attempt.getTaskId(),
                attempt.getWorkerId(),
                attempt.getLeaseToken(),
                attempt.getLeaseUntil(),
                attempt.getVersion()
        );
    }

    private ExecutionStateRecord toExecutionState(TestTaskEntity task, TaskAttemptEntity attempt) {
        return new ExecutionStateRecord(
                task.getId(),
                task.getState(),
                task.getPersistenceVersion(),
                attempt.getId(),
                attempt.getAttemptNo(),
                attempt.getState(),
                attempt.getVersion(),
                task.getCancellationRequestedAt() != null
        );
    }
}
