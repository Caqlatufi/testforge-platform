package io.testforge.runorchestrator.service.attempt;

import io.testforge.runorchestrator.entity.attempt.TaskAttemptEntity;
import io.testforge.runorchestrator.model.attempt.AttemptState;
import io.testforge.runorchestrator.model.attempt.AttemptTransitionOutcome;
import io.testforge.runorchestrator.model.attempt.AttemptTransitionResult;
import io.testforge.runorchestrator.repo.attempt.TaskAttemptRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class AttemptTransitionService {

    private final TaskAttemptRepository repository;
    private final AttemptStateMachine stateMachine;

    public AttemptTransitionService(TaskAttemptRepository repository) {
        this(repository, new AttemptStateMachine());
    }

    AttemptTransitionService(TaskAttemptRepository repository, AttemptStateMachine stateMachine) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine 不能为空");
    }

    /**
     * 使用调用方读取到的版本执行状态条件更新。
     * 相同目标状态视为至少一次调用下的幂等重放，不重复增加版本。
     */
    @Transactional
    public AttemptTransitionResult transition(
            UUID attemptId,
            long expectedVersion,
            AttemptState targetState,
            Instant transitionedAt
    ) {
        Objects.requireNonNull(attemptId, "attemptId 不能为空");
        Objects.requireNonNull(targetState, "targetState 不能为空");
        Objects.requireNonNull(transitionedAt, "transitionedAt 不能为空");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion 不能小于 0");
        }

        TaskAttemptEntity current = find(attemptId);
        if (current.getState() == targetState) {
            return result(current, AttemptTransitionOutcome.ALREADY_APPLIED);
        }
        if (current.getVersion() != expectedVersion) {
            throw conflict(current, current.getState(), expectedVersion);
        }

        AttemptState sourceState = current.getState();
        stateMachine.requireTransition(sourceState, targetState);
        int changed = repository.compareAndSetState(
                attemptId,
                sourceState,
                expectedVersion,
                targetState,
                transitionedAt
        );
        if (changed == 1) {
            return new AttemptTransitionResult(
                    attemptId,
                    targetState,
                    expectedVersion + 1,
                    AttemptTransitionOutcome.APPLIED
            );
        }

        TaskAttemptEntity raced = find(attemptId);
        if (raced.getState() == targetState) {
            return result(raced, AttemptTransitionOutcome.ALREADY_APPLIED);
        }
        throw conflict(raced, sourceState, expectedVersion);
    }

    private TaskAttemptEntity find(UUID attemptId) {
        return repository.findById(attemptId)
                .orElseThrow(() -> new AttemptNotFoundException(attemptId));
    }

    private AttemptTransitionResult result(
            TaskAttemptEntity attempt,
            AttemptTransitionOutcome outcome
    ) {
        return new AttemptTransitionResult(
                attempt.getId(),
                attempt.getState(),
                attempt.getVersion(),
                outcome
        );
    }

    private AttemptStateConflictException conflict(
            TaskAttemptEntity actual,
            AttemptState expectedState,
            long expectedVersion
    ) {
        return new AttemptStateConflictException(
                actual.getId(),
                expectedState,
                expectedVersion,
                actual.getState(),
                actual.getVersion()
        );
    }
}
