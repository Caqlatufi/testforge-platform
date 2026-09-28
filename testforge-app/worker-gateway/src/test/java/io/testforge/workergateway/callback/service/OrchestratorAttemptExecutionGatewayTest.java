package io.testforge.workergateway.callback.service;

import io.testforge.runorchestrator.model.attempt.AttemptState;
import io.testforge.runorchestrator.service.reliability.AttemptLeaseRecord;
import io.testforge.runorchestrator.service.reliability.ExecutionReliabilityService;
import io.testforge.runorchestrator.service.reliability.ExecutionStateRecord;
import io.testforge.runorchestrator.service.reliability.ExecutionTransitionCommand;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.workergateway.callback.model.CallbackStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrchestratorAttemptExecutionGatewayTest {

    private static final Instant NOW = Instant.parse("2026-09-18T06:00:00Z");

    @Mock
    private ObjectProvider<ExecutionReliabilityService> provider;

    @Mock
    private ExecutionReliabilityService reliabilityService;

    private OrchestratorAttemptExecutionGateway gateway;

    @BeforeEach
    void setUp() {
        when(provider.getIfAvailable()).thenReturn(reliabilityService);
        gateway = new OrchestratorAttemptExecutionGateway(provider);
    }

    @Test
    void wrongLeaseIsStaleAndCannotReachTerminalCas() {
        UUID attemptId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        when(reliabilityService.heartbeat(
                attemptId, "worker-1", leaseToken, NOW, NOW.plusSeconds(30)
        )).thenReturn(Optional.empty());

        AttemptCompletion result = gateway.complete(
                attemptId, "worker-1", leaseToken, CallbackStatus.PASSED,
                NOW, NOW.plusSeconds(30)
        );

        assertThat(result.accepted()).isFalse();
        verify(reliabilityService, never()).load(org.mockito.ArgumentMatchers.any());
        verify(reliabilityService, never()).compareAndSet(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void acceptedCallbackAtomicallyMapsAttemptAndTaskTerminalState() {
        UUID attemptId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        when(reliabilityService.heartbeat(
                attemptId, "worker-1", leaseToken, NOW, NOW.plusSeconds(30)
        )).thenReturn(Optional.of(new AttemptLeaseRecord(
                attemptId, taskId, "worker-1", leaseToken, NOW.plusSeconds(30), 4
        )));
        ExecutionStateRecord current = new ExecutionStateRecord(
                taskId, TaskState.RUNNING, 3, attemptId, 1, AttemptState.RUNNING, 4, false
        );
        when(reliabilityService.load(taskId)).thenReturn(current);
        when(reliabilityService.compareAndSet(
                org.mockito.ArgumentMatchers.eq(current), org.mockito.ArgumentMatchers.any()
        )).thenReturn(true);

        AttemptCompletion result = gateway.complete(
                attemptId, "worker-1", leaseToken, CallbackStatus.PASSED,
                NOW, NOW.plusSeconds(30)
        );

        assertThat(result).isEqualTo(AttemptCompletion.accepted(taskId, CallbackStatus.PASSED));
        ArgumentCaptor<ExecutionTransitionCommand> transition = ArgumentCaptor.forClass(
                ExecutionTransitionCommand.class
        );
        verify(reliabilityService).compareAndSet(
                org.mockito.ArgumentMatchers.eq(current), transition.capture()
        );
        assertThat(transition.getValue().taskState()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(transition.getValue().attemptState()).isEqualTo(AttemptState.SUCCEEDED);
    }

    @Test
    void cancellationRequestWinsAgainstAConcurrentPassedCallback() {
        UUID attemptId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        when(reliabilityService.heartbeat(
                attemptId, "worker-1", leaseToken, NOW, NOW.plusSeconds(30)
        )).thenReturn(Optional.of(new AttemptLeaseRecord(
                attemptId, taskId, "worker-1", leaseToken, NOW.plusSeconds(30), 4
        )));
        ExecutionStateRecord current = new ExecutionStateRecord(
                taskId, TaskState.RUNNING, 3, attemptId, 1, AttemptState.RUNNING, 4, true
        );
        when(reliabilityService.load(taskId)).thenReturn(current);
        when(reliabilityService.compareAndSet(
                org.mockito.ArgumentMatchers.eq(current), org.mockito.ArgumentMatchers.any()
        )).thenReturn(true);

        AttemptCompletion result = gateway.complete(
                attemptId, "worker-1", leaseToken, CallbackStatus.PASSED,
                NOW, NOW.plusSeconds(30)
        );

        assertThat(result.effectiveStatus()).isEqualTo(CallbackStatus.CANCELLED);
        ArgumentCaptor<ExecutionTransitionCommand> transition = ArgumentCaptor.forClass(
                ExecutionTransitionCommand.class
        );
        verify(reliabilityService).compareAndSet(
                org.mockito.ArgumentMatchers.eq(current), transition.capture()
        );
        assertThat(transition.getValue().taskState()).isEqualTo(TaskState.CANCELLED);
        assertThat(transition.getValue().attemptState()).isEqualTo(AttemptState.CANCELLED);
    }

    @Test
    void oldAttemptCannotOverwriteTheLatestAttempt() {
        UUID oldAttemptId = UUID.randomUUID();
        UUID newAttemptId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        when(reliabilityService.heartbeat(
                oldAttemptId, "worker-1", leaseToken, NOW, NOW.plusSeconds(30)
        )).thenReturn(Optional.of(new AttemptLeaseRecord(
                oldAttemptId, taskId, "worker-1", leaseToken, NOW.plusSeconds(30), 4
        )));
        when(reliabilityService.load(taskId)).thenReturn(new ExecutionStateRecord(
                taskId, TaskState.RUNNING, 3, newAttemptId, 2, AttemptState.RUNNING, 0, false
        ));

        AttemptCompletion result = gateway.complete(
                oldAttemptId, "worker-1", leaseToken, CallbackStatus.PASSED,
                NOW, NOW.plusSeconds(30)
        );

        assertThat(result.accepted()).isFalse();
        verify(reliabilityService, never()).compareAndSet(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        );
    }
}
