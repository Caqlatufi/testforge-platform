package io.testforge.dispatcher.stream;

import io.testforge.dispatcher.port.outbound.DispatchObservation;

/**
 * 进程内轻量观测快照；后续可由 observability 适配器转换为 Metrics/Event。
 */
public record DispatchTelemetrySnapshot(
        long published,
        long paused,
        long redisUnavailable,
        long redeliveries,
        long recoveries,
        DispatchObservation lastObservation
) {
}
