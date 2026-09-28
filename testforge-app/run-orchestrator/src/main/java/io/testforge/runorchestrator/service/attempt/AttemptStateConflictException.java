package io.testforge.runorchestrator.service.attempt;

import io.testforge.runorchestrator.model.attempt.AttemptState;

import java.util.UUID;

public class AttemptStateConflictException extends RuntimeException {

    public AttemptStateConflictException(
            UUID attemptId,
            AttemptState expectedState,
            long expectedVersion,
            AttemptState actualState,
            long actualVersion
    ) {
        super("Attempt 状态或版本已变化: id=" + attemptId
                + ", expected=" + expectedState + "@" + expectedVersion
                + ", actual=" + actualState + "@" + actualVersion);
    }
}
