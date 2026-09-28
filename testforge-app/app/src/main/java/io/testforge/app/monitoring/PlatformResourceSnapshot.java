package io.testforge.app.monitoring;

import io.testforge.dispatcher.stream.DispatchTelemetrySnapshot;

import java.util.Map;

public record PlatformResourceSnapshot(
        long deploymentWaiting,
        long processQueued,
        long processRunning,
        long processCapacity,
        long deviceQueued,
        long deviceRunning,
        long deviceCapacity,
        long deviceAvailable,
        long onlineWorkers,
        Map<String, Long> executorCapacity,
        DispatchTelemetrySnapshot dispatch
) {
    public PlatformResourceSnapshot {
        executorCapacity = Map.copyOf(executorCapacity);
    }
}
