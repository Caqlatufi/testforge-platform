package io.testforge.runorchestrator.service.scheduling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FairSchedulingPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-18T06:00:00Z");
    private static final UUID RUN_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final FairSchedulingPolicy policy = new FairSchedulingPolicy();

    @Test
    void shouldRoundRobinBetweenRunsInsteadOfLettingOneRunOwnTheBatch() {
        SchedulingCandidate a0 = candidate(RUN_A, 0, 5, NOW.minusSeconds(1));
        SchedulingCandidate a1 = candidate(RUN_A, 1, 5, NOW.minusSeconds(1));
        SchedulingCandidate a2 = candidate(RUN_A, 2, 5, NOW.minusSeconds(1));
        SchedulingCandidate b0 = candidate(RUN_B, 0, 5, NOW.minusSeconds(1));
        SchedulingCandidate b1 = candidate(RUN_B, 1, 5, NOW.minusSeconds(1));

        assertThat(policy.order(List.of(a2, b1, a0, a1, b0), NOW))
                .extracting(SchedulingCandidate::taskId)
                .containsExactly(a0.taskId(), b0.taskId(), a1.taskId(), b1.taskId(), a2.taskId());
    }

    @Test
    void shouldAgeAnOldLowPriorityCandidateUntilItCannotStarve() {
        SchedulingCandidate agedLowPriority = candidate(RUN_A, 0, 0, NOW.minusSeconds(270));
        SchedulingCandidate freshHighestPriority = candidate(RUN_B, 0, 9, NOW);

        assertThat(policy.effectivePriority(agedLowPriority, NOW)).isEqualTo(9);
        assertThat(policy.order(List.of(freshHighestPriority, agedLowPriority), NOW))
                .first()
                .isEqualTo(agedLowPriority);
    }

    private SchedulingCandidate candidate(
            UUID runId,
            int sequenceNo,
            int priority,
            Instant queuedAt
    ) {
        return new SchedulingCandidate(
                UUID.nameUUIDFromBytes((runId + "-" + sequenceNo).getBytes(StandardCharsets.UTF_8)),
                runId,
                priority,
                sequenceNo,
                queuedAt
        );
    }
}
