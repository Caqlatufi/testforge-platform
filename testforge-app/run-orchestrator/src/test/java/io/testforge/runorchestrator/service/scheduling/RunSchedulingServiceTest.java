package io.testforge.runorchestrator.service.scheduling;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunSchedulingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T06:30:00Z");

    @Test
    void shouldReturnABoundedFairCandidateSnapshotWithoutClaimingTasks() {
        SchedulingCandidateRepository repository = mock(SchedulingCandidateRepository.class);
        UUID runA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID runB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        SchedulingCandidate first = candidate(runA, 0);
        SchedulingCandidate second = candidate(runA, 1);
        SchedulingCandidate otherRun = candidate(runB, 0);
        when(repository.findQueuedCandidates(NOW)).thenReturn(List.of(first, second, otherRun));
        RunSchedulingService service = new RunSchedulingService(
                repository,
                new FairSchedulingPolicy()
        );

        List<SchedulingCandidate> selected = service.select(2, NOW);

        assertThat(selected).containsExactly(first, otherRun);
    }

    private SchedulingCandidate candidate(UUID runId, int sequence) {
        return new SchedulingCandidate(
                UUID.randomUUID(),
                runId,
                5,
                sequence,
                NOW.minusSeconds(1)
        );
    }
}
