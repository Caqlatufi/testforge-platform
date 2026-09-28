package io.testforge.runorchestrator.run.service;

import io.testforge.runorchestrator.run.model.RunView;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunEventStreamServiceTest {
    @Test
    void sendsInitialSnapshotAndUsesCurrentTruthForReconnectCorrection() {
        UUID runId = UUID.randomUUID();
        RunTaskService runs = mock(RunTaskService.class);
        RunView snapshot = mock(RunView.class);
        when(snapshot.id()).thenReturn(runId);
        when(snapshot.version()).thenReturn(7L);
        when(runs.getRun(runId)).thenReturn(snapshot);
        RunEventStreamService service = new RunEventStreamService(runs);
        try {
            assertThat(service.subscribe(runId, "old-event-id")).isNotNull();
            assertThat(service.snapshot(runId)).isSameAs(snapshot);
            verify(runs, org.mockito.Mockito.atLeast(2)).getRun(runId);
        } finally {
            service.close();
        }
    }
}
