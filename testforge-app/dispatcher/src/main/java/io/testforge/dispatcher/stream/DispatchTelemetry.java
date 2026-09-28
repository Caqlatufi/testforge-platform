package io.testforge.dispatcher.stream;

import io.testforge.dispatcher.port.outbound.DispatchObservation;
import io.testforge.dispatcher.port.outbound.DispatchObservationPort;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 无外部依赖的派发计数器，避免把 Redis payload 或 secretRefs 写入观测数据。
 */
public final class DispatchTelemetry implements DispatchObservationPort {

    private final AtomicLong published = new AtomicLong();
    private final AtomicLong paused = new AtomicLong();
    private final AtomicLong redisUnavailable = new AtomicLong();
    private final AtomicLong redeliveries = new AtomicLong();
    private final AtomicLong recoveries = new AtomicLong();
    private final AtomicReference<DispatchObservation> lastObservation = new AtomicReference<>();

    @Override
    public void observe(DispatchObservation observation) {
        switch (observation.outcome()) {
            case PUBLISHED -> published.incrementAndGet();
            case PAUSED -> paused.incrementAndGet();
            case REDIS_UNAVAILABLE -> redisUnavailable.incrementAndGet();
        }
        if (observation.deliveryAttempt() > 1) {
            redeliveries.incrementAndGet();
        }
        if (observation.connectionRecovered()) {
            recoveries.incrementAndGet();
        }
        lastObservation.set(observation);
    }

    public DispatchTelemetrySnapshot snapshot() {
        return new DispatchTelemetrySnapshot(
                published.get(),
                paused.get(),
                redisUnavailable.get(),
                redeliveries.get(),
                recoveries.get(),
                lastObservation.get()
        );
    }
}
