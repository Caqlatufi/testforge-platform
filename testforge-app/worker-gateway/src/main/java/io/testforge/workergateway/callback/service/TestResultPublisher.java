package io.testforge.workergateway.callback.service;

import io.testforge.report.model.TestResultCommand;
import io.testforge.report.port.inbound.TestResultCommandPort;
import io.testforge.workergateway.callback.model.AttemptCallbackRequest;
import io.testforge.workergateway.callback.model.CallbackStatus;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class TestResultPublisher {

    private final ObjectProvider<TestResultCommandPort> portProvider;

    public TestResultPublisher(ObjectProvider<TestResultCommandPort> portProvider) {
        this.portProvider = Objects.requireNonNull(portProvider, "portProvider 不能为空");
    }

    public void publish(
            UUID taskId,
            AttemptCallbackRequest request,
            CallbackStatus effectiveStatus
    ) {
        TestResultCommandPort port = portProvider.getIfAvailable();
        if (port == null) {
            return;
        }
        Optional<String> failureType = request.failure() == null
                ? Optional.empty()
                : Optional.of(request.failure().type());
        port.record(new TestResultCommand(
                taskId,
                Optional.of(request.attemptId()),
                effectiveStatus.name(),
                request.durationMs(),
                failureType,
                request.summary(),
                request.artifacts().stream().map(artifact -> artifact.objectKey()).toList(),
                Optional.empty()
        ));
    }
}
