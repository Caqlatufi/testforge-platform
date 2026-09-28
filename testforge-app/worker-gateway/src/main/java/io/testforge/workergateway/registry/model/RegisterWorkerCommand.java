package io.testforge.workergateway.registry.model;

import java.util.Set;

public record RegisterWorkerCommand(
        String workerId,
        String protocolVersion,
        Set<String> capabilities,
        int maxConcurrency
) {
    public RegisterWorkerCommand {
        capabilities = capabilities == null ? null : Set.copyOf(capabilities);
    }
}
