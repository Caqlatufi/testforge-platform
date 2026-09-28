package io.testforge.dispatcher.adapter.outbound;

import io.testforge.dispatcher.reliability.RecoveryEventSource;
import io.testforge.dispatcher.reliability.retry.RetryEvent;
import io.testforge.runorchestrator.service.reliability.ExecutionReliabilityService;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class OrchestratorRecoveryEventSource implements RecoveryEventSource {

    private final ExecutionReliabilityService reliabilityService;

    public OrchestratorRecoveryEventSource(ExecutionReliabilityService reliabilityService) {
        this.reliabilityService = Objects.requireNonNull(reliabilityService, "reliabilityService 不能为空");
    }

    @Override
    public List<RetryEvent> findLost(int limit) {
        return reliabilityService.findRecoverableLost(limit).stream()
                .map(record -> RetryEvent.failed(
                        record.taskId(),
                        record.attemptId(),
                        record.attemptNo(),
                        "WORKER_LOST",
                        record.occurredAt()
                ))
                .toList();
    }

    @Override
    public List<RetryEvent> findTimedOut(Instant now, int limit) {
        return reliabilityService.findTimedOut(now, limit).stream()
                .map(record -> RetryEvent.timedOut(
                        record.taskId(),
                        record.attemptId(),
                        record.attemptNo(),
                        record.occurredAt()
                ))
                .toList();
    }
}
