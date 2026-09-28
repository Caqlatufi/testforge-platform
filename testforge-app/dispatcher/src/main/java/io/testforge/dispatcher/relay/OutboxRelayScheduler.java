package io.testforge.dispatcher.relay;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Objects;

/**
 * 周期驱动 Relay。实例内使用 fixed delay 串行触发，多实例并发由 Outbox 行锁与 claim token 仲裁。
 */
public final class OutboxRelayScheduler {

    private final OutboxRelay relay;
    private final String owner;
    private final int batchSize;
    private final Duration leaseDuration;

    public OutboxRelayScheduler(
            OutboxRelay relay,
            String owner,
            int batchSize,
            Duration leaseDuration
    ) {
        this.relay = Objects.requireNonNull(relay, "relay must not be null");
        if (owner == null || owner.isBlank() || owner.length() > 120) {
            throw new IllegalArgumentException("owner 必须为长度不超过 120 的非空字符串");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize 必须在 1 到 1000 之间");
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration 必须大于 0");
        }
        this.owner = owner;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
    }

    @Scheduled(fixedDelayString = "${testforge.dispatcher.relay.fixed-delay:PT1S}")
    public RelayBatchResult relayDueEvents() {
        return relay.relayBatch(owner, batchSize, leaseDuration);
    }
}
