package io.testforge.runorchestrator.model.attempt;

import java.util.UUID;

public record AttemptTransitionResult(
        UUID attemptId,
        AttemptState state,
        long version,
        AttemptTransitionOutcome outcome
) {
}
