package io.testforge.common.event;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface ExecutionEventQueryPort {
    List<ExecutionEventView> history(UUID runId, long afterEventId, int limit);

    static ExecutionEventQueryPort noop() {
        return (runId, afterEventId, limit) -> List.of();
    }
}
