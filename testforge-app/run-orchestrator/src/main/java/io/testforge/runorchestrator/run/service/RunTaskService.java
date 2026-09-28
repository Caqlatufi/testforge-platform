package io.testforge.runorchestrator.run.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.testforge.casecatalog.workflow.compile.model.CompiledEdge;
import io.testforge.casecatalog.workflow.compile.model.CompiledNode;
import io.testforge.casecatalog.workflow.compile.model.CompiledWorkflowSnapshot;
import io.testforge.casecatalog.workflow.compile.model.PublishedWorkflowVersionView;
import io.testforge.casecatalog.workflow.compile.service.WorkflowPublishService;
import io.testforge.casecatalog.workflow.compile.service.WorkflowSnapshotJsonCodec;
import io.testforge.casecatalog.workflow.service.TestWorkflowService;
import io.testforge.cicdgateway.deployment.model.DeploymentState;
import io.testforge.cicdgateway.deployment.model.DeploymentChangedEvent;
import io.testforge.cicdgateway.deployment.service.DeploymentService;
import io.testforge.projectcatalog.model.ProjectState;
import io.testforge.projectcatalog.model.TargetView;
import io.testforge.projectcatalog.revision.ResolvedRevision;
import io.testforge.projectcatalog.revision.RevisionSelector;
import io.testforge.projectcatalog.revision.RevisionType;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import io.testforge.runorchestrator.entity.attempt.TaskAttemptEntity;
import io.testforge.runorchestrator.model.attempt.AttemptView;
import io.testforge.runorchestrator.port.dispatch.TaskDispatchRegistration;
import io.testforge.runorchestrator.port.dispatch.TaskDispatchRegistrationPort;
import io.testforge.common.event.ExecutionEventCommand;
import io.testforge.common.event.ExecutionEventPort;
import io.testforge.runorchestrator.repo.attempt.TaskAttemptRepository;
import io.testforge.runorchestrator.run.entity.TestRunEntity;
import io.testforge.runorchestrator.run.model.CancelRunCommand;
import io.testforge.runorchestrator.run.model.CreateRunCommand;
import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.run.model.RunView;
import io.testforge.runorchestrator.run.model.TaskHistoryContext;
import io.testforge.runorchestrator.run.model.TaskExecutionView;
import io.testforge.runorchestrator.run.repo.TestRunRepository;
import io.testforge.runorchestrator.task.entity.TaskDependencyEntity;
import io.testforge.runorchestrator.task.entity.TestTaskEntity;
import io.testforge.runorchestrator.task.model.TaskDependencyView;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.model.TaskView;
import io.testforge.runorchestrator.task.model.TaskTargetRevision;
import io.testforge.runorchestrator.task.repo.TaskDependencyRepository;
import io.testforge.runorchestrator.task.repo.TestTaskRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 创建 Run 时固化当前 Case 解析结果，并从该不可变执行快照创建 Task 聚合。
 * 派发 Outbox 由 dispatcher 通过后续公开 Port 接入。
 */
public class RunTaskService {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final String DEFAULT_RETRY_POLICY =
            "{\"maxAttempts\":3,\"initialBackoffSeconds\":5,\"maxBackoffSeconds\":60}";
    private static final ReentrantLock[] CREATE_LOCKS = createLocks(256);

    private final TestRunRepository runRepository;
    private final TestTaskRepository taskRepository;
    private final TaskDependencyRepository dependencyRepository;
    private final TaskAttemptRepository attemptRepository;
    private final ProjectCatalogService projectCatalogService;
    private final WorkflowPublishService workflowPublishService;
    private final TestWorkflowService workflowCatalogService;
    private final ObjectMapper objectMapper;
    private final WorkflowSnapshotJsonCodec snapshotJsonCodec;
    private final RunAggregationPolicy aggregationPolicy;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;
    private final TaskDispatchRegistrationPort dispatchRegistrationPort;
    private final ExecutionEventPort executionEventPort;
    private final DeploymentService deploymentService;

    public RunTaskService(
            TestRunRepository runRepository,
            TestTaskRepository taskRepository,
            TaskDependencyRepository dependencyRepository,
            TaskAttemptRepository attemptRepository,
            ProjectCatalogService projectCatalogService,
            WorkflowPublishService workflowPublishService,
            ObjectMapper objectMapper
    ) {
        this(
                runRepository,
                taskRepository,
                dependencyRepository,
                attemptRepository,
                projectCatalogService,
                workflowPublishService,
                objectMapper,
                TaskDispatchRegistrationPort.noop()
        );
    }

    public RunTaskService(
            TestRunRepository runRepository,
            TestTaskRepository taskRepository,
            TaskDependencyRepository dependencyRepository,
            TaskAttemptRepository attemptRepository,
            ProjectCatalogService projectCatalogService,
            WorkflowPublishService workflowPublishService,
            ObjectMapper objectMapper,
            TaskDispatchRegistrationPort dispatchRegistrationPort
    ) {
        this(
                runRepository,
                taskRepository,
                dependencyRepository,
                attemptRepository,
                projectCatalogService,
                workflowPublishService,
                objectMapper,
                new RunAggregationPolicy(),
                Clock.systemUTC(),
                UUID::randomUUID,
                dispatchRegistrationPort,
                ExecutionEventPort.noop(),
                null
        );
    }

    public RunTaskService(
            TestRunRepository runRepository,
            TestTaskRepository taskRepository,
            TaskDependencyRepository dependencyRepository,
            TaskAttemptRepository attemptRepository,
            ProjectCatalogService projectCatalogService,
            WorkflowPublishService workflowPublishService,
            ObjectMapper objectMapper,
            TaskDispatchRegistrationPort dispatchRegistrationPort,
            ExecutionEventPort executionEventPort
    ) {
        this(
                runRepository, taskRepository, dependencyRepository, attemptRepository,
                projectCatalogService, workflowPublishService, objectMapper,
                new RunAggregationPolicy(), Clock.systemUTC(), UUID::randomUUID,
                dispatchRegistrationPort, executionEventPort, null
        );
    }

    public RunTaskService(
            TestRunRepository runRepository,
            TestTaskRepository taskRepository,
            TaskDependencyRepository dependencyRepository,
            TaskAttemptRepository attemptRepository,
            ProjectCatalogService projectCatalogService,
            WorkflowPublishService workflowPublishService,
            ObjectMapper objectMapper,
            TaskDispatchRegistrationPort dispatchRegistrationPort,
            ExecutionEventPort executionEventPort,
            DeploymentService deploymentService
    ) {
        this(runRepository, taskRepository, dependencyRepository, attemptRepository,
                projectCatalogService, workflowPublishService, objectMapper,
                new RunAggregationPolicy(), Clock.systemUTC(), UUID::randomUUID,
                dispatchRegistrationPort, executionEventPort, deploymentService);
    }

    public RunTaskService(
            TestRunRepository runRepository,
            TestTaskRepository taskRepository,
            TaskDependencyRepository dependencyRepository,
            TaskAttemptRepository attemptRepository,
            ProjectCatalogService projectCatalogService,
            WorkflowPublishService workflowPublishService,
            ObjectMapper objectMapper,
            RunAggregationPolicy aggregationPolicy,
            Clock clock,
            Supplier<UUID> idGenerator
    ) {
        this(
                runRepository,
                taskRepository,
                dependencyRepository,
                attemptRepository,
                projectCatalogService,
                workflowPublishService,
                objectMapper,
                aggregationPolicy,
                clock,
                idGenerator,
                TaskDispatchRegistrationPort.noop(),
                ExecutionEventPort.noop(),
                null
        );
    }

    public RunTaskService(
            TestRunRepository runRepository,
            TestTaskRepository taskRepository,
            TaskDependencyRepository dependencyRepository,
            TaskAttemptRepository attemptRepository,
            ProjectCatalogService projectCatalogService,
            WorkflowPublishService workflowPublishService,
            ObjectMapper objectMapper,
            RunAggregationPolicy aggregationPolicy,
            Clock clock,
            Supplier<UUID> idGenerator,
            TaskDispatchRegistrationPort dispatchRegistrationPort
    ) {
        this(
                runRepository, taskRepository, dependencyRepository, attemptRepository,
                projectCatalogService, workflowPublishService, objectMapper, aggregationPolicy,
                clock, idGenerator, dispatchRegistrationPort, ExecutionEventPort.noop()
        );
    }

    public RunTaskService(
            TestRunRepository runRepository,
            TestTaskRepository taskRepository,
            TaskDependencyRepository dependencyRepository,
            TaskAttemptRepository attemptRepository,
            ProjectCatalogService projectCatalogService,
            WorkflowPublishService workflowPublishService,
            ObjectMapper objectMapper,
            RunAggregationPolicy aggregationPolicy,
            Clock clock,
            Supplier<UUID> idGenerator,
            TaskDispatchRegistrationPort dispatchRegistrationPort,
            ExecutionEventPort executionEventPort
    ) {
        this(runRepository, taskRepository, dependencyRepository, attemptRepository,
                projectCatalogService, workflowPublishService, objectMapper, aggregationPolicy, clock,
                idGenerator, dispatchRegistrationPort, executionEventPort, null);
    }

    public RunTaskService(
            TestRunRepository runRepository,
            TestTaskRepository taskRepository,
            TaskDependencyRepository dependencyRepository,
            TaskAttemptRepository attemptRepository,
            ProjectCatalogService projectCatalogService,
            WorkflowPublishService workflowPublishService,
            ObjectMapper objectMapper,
            RunAggregationPolicy aggregationPolicy,
            Clock clock,
            Supplier<UUID> idGenerator,
            TaskDispatchRegistrationPort dispatchRegistrationPort,
            ExecutionEventPort executionEventPort,
            DeploymentService deploymentService
    ) {
        this(runRepository, taskRepository, dependencyRepository, attemptRepository,
                projectCatalogService, workflowPublishService, null, objectMapper, aggregationPolicy, clock,
                idGenerator, dispatchRegistrationPort, executionEventPort, deploymentService);
    }

    public RunTaskService(
            TestRunRepository runRepository,
            TestTaskRepository taskRepository,
            TaskDependencyRepository dependencyRepository,
            TaskAttemptRepository attemptRepository,
            ProjectCatalogService projectCatalogService,
            WorkflowPublishService workflowPublishService,
            TestWorkflowService workflowCatalogService,
            ObjectMapper objectMapper,
            RunAggregationPolicy aggregationPolicy,
            Clock clock,
            Supplier<UUID> idGenerator,
            TaskDispatchRegistrationPort dispatchRegistrationPort,
            ExecutionEventPort executionEventPort,
            DeploymentService deploymentService
    ) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.dependencyRepository = Objects.requireNonNull(dependencyRepository,
                "dependencyRepository must not be null");
        this.attemptRepository = Objects.requireNonNull(attemptRepository,
                "attemptRepository must not be null");
        this.projectCatalogService = Objects.requireNonNull(projectCatalogService,
                "projectCatalogService must not be null");
        this.workflowPublishService = Objects.requireNonNull(workflowPublishService,
                "workflowPublishService must not be null");
        this.workflowCatalogService = workflowCatalogService;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null").copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.snapshotJsonCodec = new WorkflowSnapshotJsonCodec(this.objectMapper);
        this.aggregationPolicy = Objects.requireNonNull(aggregationPolicy, "aggregationPolicy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.dispatchRegistrationPort = Objects.requireNonNull(
                dispatchRegistrationPort, "dispatchRegistrationPort must not be null"
        );
        this.executionEventPort = Objects.requireNonNull(executionEventPort, "executionEventPort must not be null");
        this.deploymentService = deploymentService;
    }

    @Transactional
    public RunView createRun(CreateRunCommand command) {
        validateCreateCommand(command);
        ReentrantLock lock = creationLock(command.requestKey());
        lock.lock();
        boolean releaseOnCompletion = registerTransactionUnlock(lock);
        try {
            var existing = runRepository.findByRequestKey(command.requestKey());
            if (existing.isPresent()) {
                return requireSameCreateRequest(existing.get(), command);
            }

            TargetView target = validateAssetOwnership(command);
            PublishedWorkflowVersionView published = workflowCatalogService == null
                    ? workflowPublishService.get(command.workflowId(), command.workflowVersion())
                    : workflowCatalogService.resolveForExecution(command.workflowId(), command.workflowVersion());
            validatePublishedWorkflow(command, published);

            CompiledWorkflowSnapshot snapshot = published.compiledSnapshot();
            validateSnapshot(snapshot);
            Map<UUID, TaskTargetRevision> targetRevisions = resolveTaskRevisions(command, target, snapshot);
            String snapshotJson = snapshotJsonCodec.write(snapshot);
            String fingerprint = requestFingerprint(command, published.checksum());
            Instant now = Instant.now(clock);
            UUID runId = idGenerator.get();
            TestRunEntity run = new TestRunEntity(
                    runId,
                    command,
                    published.checksum(),
                    snapshotJson,
                    fingerprint,
                    RunState.QUEUED,
                    now
            );

            Map<UUID, Integer> incomingCounts = incomingCounts(snapshot);
            Map<UUID, UUID> taskIdsByNode = new LinkedHashMap<>();
            List<TestTaskEntity> tasks = new ArrayList<>(snapshot.nodes().size());
            List<TaskDispatchRegistration> dispatchRegistrations = new ArrayList<>();
            for (int index = 0; index < snapshot.nodes().size(); index++) {
                CompiledNode node = snapshot.nodes().get(index);
                UUID taskId = idGenerator.get();
                taskIdsByNode.put(node.id(), taskId);
                boolean queued = incomingCounts.getOrDefault(node.id(), 0) == 0;
                TestTaskEntity task = new TestTaskEntity(
                        taskId,
                        runId,
                        index,
                        node,
                        platform(node.parameters(), command.requestedPlatform()),
                        writeJson(requiredFeatures(node)),
                        writeJson(node.parameters()),
                        DEFAULT_RETRY_POLICY,
                        targetRevisions.get(node.id()),
                        command.deploymentProfileId() != null
                                ? TaskState.CREATED
                                : queued ? TaskState.QUEUED : TaskState.WAITING_DEPENDENCY,
                        now
                );
                boolean deploymentReady = true;
                if (command.deploymentProfileId() != null) {
                    if (deploymentService == null || targetRevisions.get(node.id()) == null) {
                        throw new RunValidationException("指定 DeploymentProfile 时 Target 和 Task 必须具有版本快照");
                    }
                    var deployment = deploymentService.prepare(
                            command.targetId(), command.deploymentProfileId(),
                            targetRevisions.get(node.id()).resolvedCommit(), command.deploymentEnvironment()
                    );
                    task.waitForDeployment(deployment.id(), now);
                    deploymentReady = deployment.state() == DeploymentState.READY;
                    if (deploymentReady) {
                        task.releaseDeployment(!queued, now);
                    } else if (deployment.state() == DeploymentState.FAILED
                            || deployment.state() == DeploymentState.EXPIRED) {
                        task.blockByDeployment("DEPLOYMENT_" + deployment.state(), now);
                    }
                }
                tasks.add(task);
                if (queued && deploymentReady) {
                    dispatchRegistrations.add(new TaskDispatchRegistration(
                            taskId,
                            runId,
                            node.runner(),
                            task.getResourceMode(),
                            platform(node.parameters(), command.requestedPlatform()),
                            node.sourceRef(),
                            node.scriptChecksum(),
                            node.timeoutSeconds(),
                            requiredFeatures(node),
                            node.parameters(),
                            command.priority(),
                            1,
                            now
                    ));
                }
            }
            List<TaskDependencyEntity> dependencies = snapshot.edges().stream()
                    .map(edge -> dependency(runId, edge, taskIdsByNode))
                    .toList();

            try {
                runRepository.saveAndFlush(run);
                taskRepository.saveAll(tasks);
                dependencyRepository.saveAll(dependencies);
                dependencyRepository.flush();
                dispatchRegistrations.forEach(dispatchRegistrationPort::register);
                appendEvent(runId, null, null, "RUN_CREATED", now, Map.of(
                        "state", RunState.QUEUED.name(),
                        "processConcurrency", command.processConcurrency(),
                        "deviceConcurrency", command.deviceConcurrency(),
                        "taskCount", tasks.size()
                ));
                tasks.forEach(task -> appendEvent(
                        runId, task.getId(), null,
                        task.getState() == TaskState.QUEUED
                                ? "TASK_QUEUED"
                                : task.getState() == TaskState.WAITING_DEPLOYMENT
                                ? "TASK_WAITING_DEPLOYMENT"
                                : "TASK_WAITING_DEPENDENCY",
                        now,
                        Map.of(
                                "state", task.getState().name(),
                                "resourceMode", task.getResourceMode().name(),
                                "sequenceNo", task.getSequenceNo()
                        )
                ));
            } catch (DataIntegrityViolationException exception) {
                throw new RunIdempotencyConflictException(
                        "Run 创建与现有幂等键或 Workflow 节点冲突: " + command.requestKey()
                );
            }
            return toView(run, tasks, dependencies);
        } finally {
            if (!releaseOnCompletion) {
                lock.unlock();
            }
        }
    }

    @Transactional(readOnly = true)
    public RunView getRun(UUID runId) {
        if (runId == null) {
            throw new RunValidationException("runId 不能为空");
        }
        TestRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException("Run 不存在: " + runId));
        return loadView(run);
    }

    @Transactional(readOnly = true)
    public CompiledWorkflowSnapshot getExecutionSnapshot(UUID runId) {
        if (runId == null) {
            throw new RunValidationException("runId 不能为空");
        }
        TestRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException("Run 不存在: " + runId));
        return snapshotJsonCodec.read(run.getWorkflowSnapshot());
    }

    @Transactional(readOnly = true)
    public Optional<RunView> findByRequestKey(UUID requestKey) {
        if (requestKey == null) return Optional.empty();
        return runRepository.findByRequestKey(requestKey).map(this::loadView);
    }

    @Transactional
    public RunView assignComparisonGroup(UUID runId, UUID comparisonGroupId) {
        if (runId == null || comparisonGroupId == null) {
            throw new RunValidationException("runId 和 comparisonGroupId 不能为空");
        }
        TestRunEntity run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new RunNotFoundException("Run 不存在: " + runId));
        run.assignComparisonGroup(comparisonGroupId);
        runRepository.saveAndFlush(run);
        return loadView(run);
    }

    /**
     * CI/CD Deployment 是 Task 派发前的硬门禁。回调与任务释放使用同一事务，
     * 这样不会出现 Deployment 已 READY、Task 却永久停在等待态的中间状态。
     */
    @EventListener
    @Transactional
    public void onDeploymentChanged(DeploymentChangedEvent event) {
        if (event == null || event.deploymentId() == null) {
            return;
        }
        if (event.state() != DeploymentState.READY
                && event.state() != DeploymentState.FAILED
                && event.state() != DeploymentState.EXPIRED) {
            return;
        }
        List<TestTaskEntity> affected = taskRepository
                .findAllByDeploymentIdOrderBySequenceNoAsc(event.deploymentId());
        if (affected.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        Map<UUID, List<TestTaskEntity>> byRun = affected.stream()
                .collect(Collectors.groupingBy(
                        TestTaskEntity::getRunId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        for (Map.Entry<UUID, List<TestTaskEntity>> entry : byRun.entrySet()) {
            UUID runId = entry.getKey();
            List<TaskDependencyEntity> dependencies = dependencyRepository
                    .findAllByRunIdOrderBySuccessorTaskIdAscPredecessorTaskIdAsc(runId);
            Set<UUID> successors = dependencies.stream()
                    .map(TaskDependencyEntity::getSuccessorTaskId)
                    .collect(Collectors.toSet());

            for (TestTaskEntity task : entry.getValue()) {
                if (event.state() == DeploymentState.READY) {
                    task.releaseDeployment(successors.contains(task.getId()), now);
                } else {
                    task.blockByDeployment("DEPLOYMENT_" + event.state(), now);
                }
            }
            taskRepository.saveAllAndFlush(entry.getValue());

            TestRunEntity run = runRepository.findByIdForUpdate(runId)
                    .orElseThrow(() -> new RunNotFoundException("Run 不存在: " + runId));
            List<TestTaskEntity> allTasks = taskRepository.findAllByRunIdOrderBySequenceNoAsc(runId);
            if (event.state() == DeploymentState.READY) {
                allTasks.stream()
                        .filter(task -> task.getState() == TaskState.QUEUED)
                        .forEach(task -> dispatchRegistrationPort.register(dispatchRegistration(run, task, now)));
            }
            RunState aggregate = aggregationPolicy.determine(
                    run.getCancellationRequestedAt() != null,
                    allTasks
            );
            run.applyAggregateState(aggregate, now);
            runRepository.save(run);
            appendEvent(runId, null, null, "DEPLOYMENT_" + event.state(), now, Map.of(
                    "deploymentId", event.deploymentId().toString(),
                    "state", event.state().name(),
                    "taskCount", entry.getValue().size()
            ));
        }
    }

    @Transactional(readOnly = true)
    public List<RunView> listRuns() {
        return runRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::loadView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RunView> listRunsByTestJob(UUID testJobId) {
        return runRepository.findAllByTestJobIdOrderByCreatedAtDesc(testJobId).stream()
                .map(this::loadView)
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskExecutionView getTaskExecution(UUID runId, UUID taskId) {
        RunView run = getRun(runId);
        TaskView task = run.tasks().stream()
                .filter(candidate -> candidate.id().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new RunValidationException("Task 不属于指定 Run: " + taskId));
        var project = projectCatalogService.requireProjectView(run.projectId());
        var target = project.targets().stream()
                .filter(candidate -> candidate.id().equals(run.targetId()))
                .findFirst()
                .orElseThrow(() -> new RunValidationException("Run 的 Target 不存在: " + run.targetId()));
        var environment = run.environmentId() == null
                ? null : projectCatalogService.requireEnvironmentView(run.environmentId());
        return new TaskExecutionView(
                run.projectId(), run.targetId(), run.environmentId(),
                effectiveEndpoint(task, environment == null ? null : environment.endpoint()),
                effectiveEnvironmentConfig(task, environment == null ? Map.of() : environment.config()),
                environment == null ? Map.of() : environment.secretRefs(), task
        );
    }

    private String effectiveEndpoint(TaskView task, String fallback) {
        if (task.deploymentId() == null || deploymentService == null) return fallback;
        var deployment = deploymentService.get(task.deploymentId());
        if (deployment.state() != DeploymentState.READY) throw new RunValidationException("Task Deployment 尚未 READY");
        return deployment.endpoint() == null || deployment.endpoint().isBlank() ? fallback : deployment.endpoint();
    }

    private Map<String, Object> effectiveEnvironmentConfig(TaskView task, Map<String, Object> fallback) {
        if (task.deploymentId() == null || deploymentService == null) return fallback;
        var deployment = deploymentService.get(task.deploymentId());
        Map<String, Object> result = new LinkedHashMap<>(fallback);
        result.put("deploymentId", deployment.id().toString());
        if (deployment.artifactUri() != null) result.put("artifactUri", deployment.artifactUri());
        if (deployment.artifactDigest() != null) result.put("artifactDigest", deployment.artifactDigest());
        return result;
    }

    @Transactional(readOnly = true)
    public TaskHistoryContext getTaskHistoryContext(UUID taskId) {
        if (taskId == null) {
            throw new RunValidationException("taskId 不能为空");
        }
        TestTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new RunNotFoundException("Task 不存在: " + taskId));
        return new TaskHistoryContext(task.getRunId(), task.getId(), task.getCaseId());
    }

    @Transactional
    public RunView requestCancellation(UUID runId, CancelRunCommand command) {
        if (runId == null) {
            throw new RunValidationException("runId 不能为空");
        }
        validateCancelCommand(command);
        TestRunEntity run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new RunNotFoundException("Run 不存在: " + runId));

        if (run.getCancellationRequestKey() != null) {
            if (run.getCancellationRequestKey().equals(command.requestKey())
                    && Objects.equals(run.getCancellationReason(), command.reason().trim())) {
                return loadView(run);
            }
            if (run.getCancellationRequestKey().equals(command.requestKey())) {
                throw new RunIdempotencyConflictException("相同取消 requestKey 对应了不同 reason");
            }
            return loadView(run);
        }
        if (run.getState().isTerminal()) {
            throw new RunStateConflictException("终态 Run 不能取消: " + run.getState());
        }

        Instant now = Instant.now(clock);
        String reason = command.reason().trim();
        run.requestCancellation(command.requestKey(), reason, now);
        List<TestTaskEntity> tasks = taskRepository.findAllByRunIdOrderBySequenceNoAsc(runId);
        tasks.forEach(task -> task.requestCancellation(reason, now));
        run.applyAggregateState(aggregationPolicy.determine(true, tasks), now);
        taskRepository.saveAllAndFlush(tasks);
        releaseUnsharedActiveDeployments(runId, tasks, reason);
        runRepository.saveAndFlush(run);
        appendEvent(runId, null, null, "RUN_CANCELLATION_REQUESTED", now, Map.of(
                "state", run.getState().name(), "reason", reason
        ));
        tasks.stream().filter(task -> task.getState() == TaskState.CANCELLED).forEach(task ->
                appendEvent(runId, task.getId(), null, "TASK_CANCELLED", now, Map.of("reason", reason))
        );
        return toView(run, tasks, dependencyRepository
                .findAllByRunIdOrderBySuccessorTaskIdAscPredecessorTaskIdAsc(runId));
    }

    private void releaseUnsharedActiveDeployments(UUID cancelledRunId, List<TestTaskEntity> cancelledTasks,
                                                  String reason) {
        if (deploymentService == null) return;
        cancelledTasks.stream()
                .map(TestTaskEntity::getDeploymentId)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(deploymentId -> {
                    List<TestTaskEntity> references = taskRepository
                            .findAllByDeploymentIdOrderBySequenceNoAsc(deploymentId);
                    boolean hasOtherActiveReference = references.stream()
                            .anyMatch(task -> !task.getRunId().equals(cancelledRunId)
                                    && !task.getState().isTerminal());
                    boolean cancelledRunStillActive = references.stream()
                            .anyMatch(task -> task.getRunId().equals(cancelledRunId)
                                    && !task.getState().isTerminal());
                    if (!hasOtherActiveReference && !cancelledRunStillActive) {
                        deploymentService.failActive(deploymentId, "关联 Run 已取消: " + reason);
                    }
                });
    }

    @Transactional
    public int failOrphanedActiveDeployments() {
        if (deploymentService == null) return 0;
        int failed = 0;
        for (UUID deploymentId : taskRepository.findDistinctDeploymentIds()) {
            List<TestTaskEntity> references = taskRepository
                    .findAllByDeploymentIdOrderBySequenceNoAsc(deploymentId);
            if (!references.isEmpty() && references.stream().allMatch(task -> task.getState().isTerminal())
                    && deploymentService.failActive(deploymentId,
                    "Deployment 已无活动 Task 引用，执行编排器完成孤儿回收")) {
                failed++;
            }
        }
        return failed;
    }

    @Transactional
    public RunView refreshAggregate(UUID runId) {
        if (runId == null) {
            throw new RunValidationException("runId 不能为空");
        }
        TestRunEntity run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new RunNotFoundException("Run 不存在: " + runId));
        List<TestTaskEntity> tasks = taskRepository.findAllByRunIdOrderBySequenceNoAsc(runId);
        RunState aggregateState = aggregationPolicy.determine(
                run.getCancellationRequestedAt() != null,
                tasks
        );
        run.applyAggregateState(aggregateState, Instant.now(clock));
        runRepository.saveAndFlush(run);
        return toView(run, tasks, dependencyRepository
                .findAllByRunIdOrderBySuccessorTaskIdAscPredecessorTaskIdAsc(runId));
    }

    private RunView requireSameCreateRequest(TestRunEntity existing, CreateRunCommand command) {
        String fingerprint = requestFingerprint(command, existing.getWorkflowChecksum());
        if (!existing.matches(command, fingerprint)) {
            throw new RunIdempotencyConflictException(
                    "相同 requestKey 对应了不同 Run 创建参数: " + command.requestKey()
            );
        }
        return loadView(existing);
    }

    private TargetView validateAssetOwnership(CreateRunCommand command) {
        var project = projectCatalogService.requireProjectView(command.projectId());
        if (project.state() != ProjectState.ACTIVE) {
            throw new RunValidationException("只有 ACTIVE 项目可以创建 Run: " + command.projectId());
        }
        TargetView target = project.targets().stream()
                .filter(candidate -> candidate.id().equals(command.targetId()))
                .findFirst()
                .orElseThrow(() -> new RunValidationException(
                        "targetId 不属于 projectId: " + command.targetId()
                ));
        if (command.environmentId() != null) {
            var environment = projectCatalogService.requireEnvironmentView(command.environmentId());
            if (!environment.enabled()) {
                throw new RunValidationException("environmentId 已停用: " + command.environmentId());
            }
        }
        return target;
    }

    private Map<UUID, TaskTargetRevision> resolveTaskRevisions(
            CreateRunCommand command,
            TargetView target,
            CompiledWorkflowSnapshot snapshot
    ) {
        Set<UUID> nodeIds = snapshot.nodes().stream().map(CompiledNode::id).collect(Collectors.toSet());
        command.taskRevisionOverrides().keySet().stream()
                .filter(nodeId -> !nodeIds.contains(nodeId))
                .findFirst()
                .ifPresent(nodeId -> {
                    throw new RunValidationException(
                            "taskRevisionOverrides 引用了不存在的 workflowNodeId: " + nodeId
                    );
                });

        RevisionSelector defaultSelector = command.targetRevision();
        if (defaultSelector == null && target.repositoryUrl() != null) {
            defaultSelector = new RevisionSelector(RevisionType.DEFAULT_BRANCH, null);
        }
        if (defaultSelector == null && command.taskRevisionOverrides().isEmpty()) {
            return Map.of();
        }

        Map<RevisionSelector, ResolvedRevision> resolvedBySelector = new LinkedHashMap<>();
        Map<UUID, TaskTargetRevision> result = new LinkedHashMap<>();
        for (CompiledNode node : snapshot.nodes()) {
            RevisionSelector selector = command.taskRevisionOverrides().getOrDefault(node.id(), defaultSelector);
            if (selector == null) {
                throw new RunValidationException(
                        "Target 未配置默认仓库版本，且 Task 未指定版本: " + node.id()
                );
            }
            ResolvedRevision resolved = resolvedBySelector.computeIfAbsent(
                    selector,
                    value -> projectCatalogService.resolveRevision(target.id(), value)
            );
            result.put(node.id(), new TaskTargetRevision(
                    target.repositoryUrl(),
                    resolved.requestedType(),
                    resolved.requestedValue(),
                    resolved.commitSha(),
                    resolved.resolvedAt()
            ));
        }
        return Map.copyOf(result);
    }

    private void validatePublishedWorkflow(
            CreateRunCommand command,
            PublishedWorkflowVersionView published
    ) {
        if (!published.projectId().equals(command.projectId())) {
            throw new RunValidationException("Workflow 不属于指定项目: " + command.workflowId());
        }
        if (!published.targetId().equals(command.targetId())) {
            throw new RunValidationException("Workflow 不属于指定被测对象: " + command.workflowId());
        }
        if (!published.workflowId().equals(command.workflowId())
                || published.version() != command.workflowVersion()) {
            throw new RunValidationException("Workflow 发布版本与创建请求不一致");
        }
    }

    private void validateSnapshot(CompiledWorkflowSnapshot snapshot) {
        if (snapshot == null || snapshot.nodes().isEmpty()) {
            throw new RunValidationException("Workflow 编译快照必须包含可执行节点");
        }
        Map<UUID, CompiledNode> nodes = snapshot.nodes().stream().collect(Collectors.toMap(
                CompiledNode::id,
                node -> node,
                (left, right) -> {
                    throw new RunValidationException("Workflow 快照包含重复节点: " + left.id());
                },
                LinkedHashMap::new
        ));
        for (CompiledEdge edge : snapshot.edges()) {
            if (!nodes.containsKey(edge.predecessorNodeId()) || !nodes.containsKey(edge.successorNodeId())) {
                throw new RunValidationException("Workflow 快照依赖边引用不存在的节点");
            }
        }
        if (snapshot.topologicalOrder().size() != nodes.size()
                || !new HashSet<>(snapshot.topologicalOrder()).equals(nodes.keySet())) {
            throw new RunValidationException("Workflow 快照拓扑顺序与节点集合不一致");
        }
    }

    private TaskDependencyEntity dependency(
            UUID runId,
            CompiledEdge edge,
            Map<UUID, UUID> taskIdsByNode
    ) {
        UUID predecessor = taskIdsByNode.get(edge.predecessorNodeId());
        UUID successor = taskIdsByNode.get(edge.successorNodeId());
        if (predecessor == null || successor == null) {
            throw new RunValidationException("Workflow 依赖边无法映射到 Task");
        }
        return new TaskDependencyEntity(idGenerator.get(), runId, predecessor, successor, edge.condition());
    }

    private Map<UUID, Integer> incomingCounts(CompiledWorkflowSnapshot snapshot) {
        Map<UUID, Integer> counts = new HashMap<>();
        snapshot.nodes().forEach(node -> counts.put(node.id(), 0));
        snapshot.edges().forEach(edge -> counts.compute(edge.successorNodeId(),
                (ignored, count) -> count == null ? 1 : count + 1));
        return counts;
    }

    private String platform(Map<String, Object> parameters) {
        Object value = parameters.get("platform");
        if (value == null) {
            return null;
        }
        if (!(value instanceof String platform) || platform.isBlank() || platform.length() > 64) {
            throw new RunValidationException("Task 参数 platform 必须是长度不超过 64 的非空字符串");
        }
        return platform.trim();
    }

    private String platform(Map<String, Object> parameters, String requestedPlatform) {
        String fromCase = platform(parameters);
        if (requestedPlatform == null || requestedPlatform.isBlank()) return fromCase;
        String requested = requestedPlatform.trim().toUpperCase(java.util.Locale.ROOT);
        if (fromCase != null && !"ANY".equalsIgnoreCase(fromCase) && !requested.equalsIgnoreCase(fromCase)) {
            throw new RunValidationException("Case 平台 " + fromCase + " 与 Test Job 平台 " + requested + " 不匹配");
        }
        return requested;
    }

    private List<String> requiredFeatures(Map<String, Object> parameters) {
        Object value = parameters.get("requiredFeatures");
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Collection<?> collection)) {
            throw new RunValidationException("Task 参数 requiredFeatures 必须是字符串数组");
        }
        List<String> features = collection.stream().map(item -> {
            if (!(item instanceof String feature) || feature.isBlank() || feature.length() > 64) {
                throw new RunValidationException("requiredFeatures 元素必须是长度不超过 64 的非空字符串");
            }
            return feature.trim();
        }).distinct().sorted().toList();
        if (features.size() > 64) {
            throw new RunValidationException("requiredFeatures 不能超过 64 项");
        }
        return features;
    }

    private List<String> requiredFeatures(CompiledNode node) {
        java.util.TreeSet<String> features = new java.util.TreeSet<>(requiredFeatures(node.parameters()));
        features.addAll(node.executionRequirement().capabilities());
        if (features.size() > 32) {
            throw new RunValidationException("Case 资源 capabilities 与参数 requiredFeatures 合计不能超过 32 项");
        }
        return List.copyOf(features);
    }

    private RunView loadView(TestRunEntity run) {
        UUID runId = run.getId();
        return toView(
                run,
                taskRepository.findAllByRunIdOrderBySequenceNoAsc(runId),
                dependencyRepository.findAllByRunIdOrderBySuccessorTaskIdAscPredecessorTaskIdAsc(runId)
        );
    }

    private RunView toView(
            TestRunEntity run,
            List<TestTaskEntity> tasks,
            List<TaskDependencyEntity> dependencies
    ) {
        Map<UUID, List<AttemptView>> attemptsByTask = loadAttempts(tasks);
        Map<UUID, List<TaskDependencyView>> dependenciesBySuccessor = dependencies.stream()
                .map(dependency -> new TaskDependencyView(
                        dependency.getPredecessorTaskId(),
                        dependency.getSuccessorTaskId(),
                        dependency.getCondition()
                ))
                .collect(Collectors.groupingBy(
                        TaskDependencyView::successorTaskId,
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)
                ));
        List<TaskView> taskViews = tasks.stream()
                .map(task -> toView(
                        task,
                        dependenciesBySuccessor.getOrDefault(task.getId(), List.of()),
                        attemptsByTask.getOrDefault(task.getId(), List.of())
                ))
                .toList();
        Map<TaskState, Long> taskCounts = tasks.stream().collect(Collectors.groupingBy(
                TestTaskEntity::getState,
                () -> new EnumMap<>(TaskState.class),
                Collectors.counting()
        ));
        return new RunView(
                run.getId(),
                run.getProjectId(),
                run.getTargetId(),
                run.getEnvironmentId(),
                run.getWorkflowId(),
                run.getWorkflowVersion(),
                run.getWorkflowChecksum(),
                run.getState(),
                run.getPriority(),
                run.getMaxConcurrency(),
                run.getProcessConcurrency(),
                run.getDeviceConcurrency(),
                run.getRequestKey(),
                run.getComparisonGroupId(),
                run.getPersistenceVersion(),
                run.getCancellationRequestedAt(),
                run.getCancellationReason(),
                run.getCreatedAt(),
                run.getUpdatedAt(),
                run.getCompletedAt(),
                taskCounts,
                taskViews,
                run.getTestJobId(),
                run.getJobConfigVersion(),
                run.getTestJobSnapshot()
        );
    }

    private Map<UUID, List<AttemptView>> loadAttempts(List<TestTaskEntity> tasks) {
        if (tasks.isEmpty()) {
            return Map.of();
        }
        return attemptRepository.findAllByTaskIdInOrderByTaskIdAscAttemptNoAsc(
                        tasks.stream().map(TestTaskEntity::getId).toList()
                ).stream()
                .map(this::toView)
                .collect(Collectors.groupingBy(
                        AttemptView::taskId,
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)
                ));
    }

    private AttemptView toView(TaskAttemptEntity attempt) {
        return new AttemptView(
                attempt.getId(),
                attempt.getTaskId(),
                attempt.getAttemptNo(),
                attempt.getWorkerId(),
                attempt.getLeaseUntil(),
                attempt.getState(),
                attempt.getVersion(),
                attempt.getCreatedAt(),
                attempt.getUpdatedAt()
        );
    }

    private TaskView toView(
            TestTaskEntity task,
            List<TaskDependencyView> dependencies,
            List<AttemptView> attempts
    ) {
        return new TaskView(
                task.getId(),
                task.getRunId(),
                task.getSequenceNo(),
                task.getWorkflowNodeId(),
                task.getSourcePath(),
                task.getSourceType(),
                task.getExecutableType(),
                task.getCaseId(),
                task.getScriptVersionId(),
                task.getScriptVersion(),
                task.isRequired(),
                task.getRunner(),
                task.getPlatform(),
                readJson(task.getRequiredFeatures(), STRING_LIST_TYPE, "Task requiredFeatures"),
                task.getResourceMode(),
                task.getInteractionMode(),
                task.getResourceProfile(),
                task.getLeaseScope(),
                task.getResourceSessionKey(),
                task.getSchedulingWaitReason(),
                task.getTargetRevision(),
                task.getDeploymentId(),
                task.getSourceRef(),
                task.getScriptChecksum(),
                task.getTimeoutSeconds(),
                readJson(task.getParametersJson(), OBJECT_MAP_TYPE, "Task parameters"),
                task.getState(),
                task.getPersistenceVersion(),
                task.getBlockedByTaskId(),
                task.getBlockedReason(),
                task.getCancellationRequestedAt(),
                task.getCancellationReason(),
                task.getRetryAvailableAt(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt(),
                dependencies,
                attempts
        );
    }

    private String requestFingerprint(CreateRunCommand command, String workflowChecksum) {
        String canonical = String.join("|",
                command.projectId().toString(),
                command.targetId().toString(),
                Objects.toString(command.environmentId(), "PIPELINE_ENVIRONMENT"),
                command.workflowId().toString(),
                Integer.toString(command.workflowVersion()),
                Integer.toString(command.priority()),
                Integer.toString(command.maxConcurrency()),
                Integer.toString(command.processConcurrency()),
                Integer.toString(command.deviceConcurrency()),
                workflowChecksum,
                selectorFingerprint(command.targetRevision()),
                Objects.toString(command.deploymentProfileId(), "NO_DEPLOYMENT"),
                Objects.toString(command.deploymentEnvironment(), "DEFAULT_DEPLOYMENT_ENVIRONMENT"),
                Objects.toString(command.requestedPlatform(), "ANY_PLATFORM"),
                Objects.toString(command.testJobId(), "DIRECT_RUN"),
                Long.toString(command.jobConfigVersion()),
                Objects.toString(command.testJobSnapshot(), "NO_JOB_SNAPSHOT"),
                command.taskRevisionOverrides().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                        .map(entry -> entry.getKey() + "=" + selectorFingerprint(entry.getValue()))
                        .collect(Collectors.joining(","))
        );
        return sha256(canonical);
    }

    private TaskDispatchRegistration dispatchRegistration(
            TestRunEntity run,
            TestTaskEntity task,
            Instant queuedAt
    ) {
        return new TaskDispatchRegistration(
                task.getId(),
                run.getId(),
                task.getRunner(),
                task.getResourceMode(),
                task.getPlatform(),
                task.getSourceRef(),
                task.getScriptChecksum(),
                task.getTimeoutSeconds(),
                readJson(task.getRequiredFeatures(), STRING_LIST_TYPE, "Task requiredFeatures"),
                readJson(task.getParametersJson(), OBJECT_MAP_TYPE, "Task parameters"),
                run.getPriority(),
                1,
                queuedAt
        );
    }

    private void appendEvent(
            UUID runId, UUID taskId, UUID attemptId, String type, Instant occurredAt, Map<String, Object> payload
    ) {
        String identity = runId + "|" + Objects.toString(taskId, "-") + "|"
                + Objects.toString(attemptId, "-") + "|" + type;
        executionEventPort.append(new ExecutionEventCommand(
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                runId, taskId, attemptId, type, occurredAt, payload
        ));
    }

    private String selectorFingerprint(RevisionSelector selector) {
        if (selector == null) {
            return "DEFAULT_IF_CONFIGURED";
        }
        return selector.type() + ":" + Objects.toString(selector.value(), "");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new RunValidationException("Task 参数无法序列化为 JSON");
        }
    }

    private <T> T readJson(String value, TypeReference<T> type, String label) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(label + " 持久化数据损坏", exception);
        }
    }

    private void validateCreateCommand(CreateRunCommand command) {
        if (command == null) {
            throw new RunValidationException("创建 Run 命令不能为空");
        }
        if (command.projectId() == null || command.targetId() == null
                || command.workflowId() == null || command.requestKey() == null) {
            throw new RunValidationException(
                    "projectId、targetId、workflowId 和 requestKey 不能为空"
            );
        }
        if (command.workflowVersion() < 1) {
            throw new RunValidationException("workflowVersion 必须大于 0");
        }
        if (command.priority() < 0 || command.priority() > 9) {
            throw new RunValidationException("priority 必须在 0 到 9 之间");
        }
        if (command.maxConcurrency() < 1 || command.maxConcurrency() > 20) {
            throw new RunValidationException("maxConcurrency 必须在 1 到 20 之间");
        }
    }

    private void validateCancelCommand(CancelRunCommand command) {
        if (command == null || command.requestKey() == null) {
            throw new RunValidationException("取消命令和 requestKey 不能为空");
        }
        if (command.reason() == null || command.reason().isBlank() || command.reason().trim().length() > 500) {
            throw new RunValidationException("取消 reason 必须为长度不超过 500 的非空字符串");
        }
    }

    private ReentrantLock creationLock(UUID requestKey) {
        return CREATE_LOCKS[Math.floorMod(requestKey.hashCode(), CREATE_LOCKS.length)];
    }

    /**
     * 事务代理在方法返回后才提交，因此锁必须延迟到 afterCompletion 才能释放。
     */
    private boolean registerTransactionUnlock(ReentrantLock lock) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                lock.unlock();
            }
        });
        return true;
    }

    private static ReentrantLock[] createLocks(int size) {
        ReentrantLock[] locks = new ReentrantLock[size];
        for (int index = 0; index < size; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }
}
