package io.testforge.runorchestrator.comparison.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.runorchestrator.comparison.entity.ComparisonRunEntity;
import io.testforge.runorchestrator.comparison.model.ComparisonRunView;
import io.testforge.runorchestrator.comparison.model.CreateComparisonRunCommand;
import io.testforge.runorchestrator.comparison.repo.ComparisonRunRepository;
import io.testforge.runorchestrator.run.model.CreateRunCommand;
import io.testforge.runorchestrator.run.model.RunView;
import io.testforge.runorchestrator.run.service.RunIdempotencyConflictException;
import io.testforge.runorchestrator.run.service.RunNotFoundException;
import io.testforge.runorchestrator.run.service.RunTaskService;
import io.testforge.runorchestrator.run.service.RunValidationException;
import io.testforge.runorchestrator.task.model.TaskTargetRevision;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public class ComparisonRunService {
    private static final ReentrantLock[] LOCKS = createLocks();
    private final ComparisonRunRepository repository;
    private final RunTaskService runs;
    private final ObjectMapper objectMapper;

    public ComparisonRunService(ComparisonRunRepository repository, RunTaskService runs, ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository);
        this.runs = Objects.requireNonNull(runs);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Transactional
    public ComparisonRunView create(CreateComparisonRunCommand command) {
        String fingerprint = fingerprint(command);
        ReentrantLock lock = LOCKS[Math.floorMod(command.requestKey().hashCode(), LOCKS.length)];
        lock.lock();
        boolean releaseOnCompletion = registerTransactionUnlock(lock);
        try {
            var existing = repository.findByRequestKey(command.requestKey());
            if (existing.isPresent()) {
                if (!existing.get().matches(fingerprint)) {
                    throw new RunIdempotencyConflictException("ComparisonRun 幂等键已用于不同请求: " + command.requestKey());
                }
                return toView(existing.get());
            }
            UUID groupId = UUID.randomUUID();
            RunView baseline = runs.createRun(runCommand(command, command.baselineRevision(),
                    childKey(command.requestKey(), "baseline"), command.baselineTestJobSnapshot()));
            RunView candidate = runs.createRun(runCommand(command, command.candidateRevision(),
                    childKey(command.requestKey(), "candidate"), command.candidateTestJobSnapshot()));
            String baselineCommit = resolvedCommit(baseline);
            String candidateCommit = resolvedCommit(candidate);
            if (baselineCommit.equals(candidateCommit)) {
                throw new RunValidationException("baseline 与 candidate 解析到同一 Commit，无法形成有效差分");
            }
            runs.assignComparisonGroup(baseline.id(), groupId);
            runs.assignComparisonGroup(candidate.id(), groupId);
            ComparisonRunEntity entity = new ComparisonRunEntity(
                    groupId, command, fingerprint, baseline.id(), baselineCommit,
                    candidate.id(), candidateCommit, Instant.now()
            );
            repository.saveAndFlush(entity);
            return toView(entity);
        } finally {
            if (!releaseOnCompletion) lock.unlock();
        }
    }

    @Transactional(readOnly = true)
    public ComparisonRunView get(UUID id) {
        return repository.findById(id).map(this::toView)
                .orElseThrow(() -> new RunNotFoundException("ComparisonRun 不存在: " + id));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ComparisonRunView> findByRequestKey(UUID requestKey) {
        if (requestKey == null) return java.util.Optional.empty();
        return repository.findByRequestKey(requestKey).map(this::toView);
    }

    private CreateRunCommand runCommand(CreateComparisonRunCommand command,
                                        io.testforge.projectcatalog.revision.RevisionSelector revision, UUID requestKey,
                                        String testJobSnapshot) {
        return new CreateRunCommand(command.projectId(), command.targetId(), command.environmentId(),
                command.workflowId(), command.workflowVersion(), command.priority(), command.maxConcurrency(),
                command.processConcurrency(), command.deviceConcurrency(), requestKey, revision, Map.of(),
                command.deploymentProfileId(), command.testJobId(), command.jobConfigVersion(), testJobSnapshot,
                command.deploymentEnvironment(), command.requestedPlatform());
    }

    private ComparisonRunView toView(ComparisonRunEntity entity) {
        return new ComparisonRunView(entity.getId(), entity.getRequestKey(), entity.getProjectId(),
                entity.getTargetId(), entity.getEnvironmentId(), entity.getWorkflowId(), entity.getWorkflowVersion(),
                runs.getRun(entity.getBaselineRunId()), runs.getRun(entity.getCandidateRunId()),
                entity.getBaselineRequestedType(), entity.getBaselineRequestedValue(), entity.getBaselineResolvedCommit(),
                entity.getCandidateRequestedType(), entity.getCandidateRequestedValue(), entity.getCandidateResolvedCommit(),
                entity.getCreatedAt(), entity.getTestJobId());
    }

    private String resolvedCommit(RunView run) {
        return run.tasks().stream().map(task -> task.targetRevision()).filter(Objects::nonNull)
                .map(TaskTargetRevision::resolvedCommit).distinct().reduce((left, right) -> {
                    throw new RunValidationException("ComparisonRun 不允许 Task 使用不同 Commit");
                }).orElseThrow(() -> new RunValidationException("被测目标未配置代码仓库，无法解析版本"));
    }

    private String fingerprint(CreateComparisonRunCommand command) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(command);
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法生成 ComparisonRun 请求指纹", exception);
        }
    }

    private static UUID childKey(UUID requestKey, String side) {
        return UUID.nameUUIDFromBytes((requestKey + ":" + side).getBytes(StandardCharsets.UTF_8));
    }

    private static ReentrantLock[] createLocks() {
        ReentrantLock[] locks = new ReentrantLock[64];
        for (int index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
        return locks;
    }

    private boolean registerTransactionUnlock(ReentrantLock lock) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return false;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) { lock.unlock(); }
        });
        return true;
    }
}
