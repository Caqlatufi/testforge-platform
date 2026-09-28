package io.testforge.dispatcher.reliability.lease;

import java.time.Duration;
import java.util.Objects;

/**
 * Attempt 租约的时间参数。
 *
 * <p>Worker 每 5 秒发送一次心跳；租约窗口默认覆盖三个心跳周期，允许短暂的网络抖动。
 * Reaper 只负责发现和标记丢失，不在这里决定重试次数或退避。</p>
 */
public record LeasePolicy(
        Duration heartbeatInterval,
        Duration leaseDuration,
        int reaperBatchSize
) {

    public static final Duration REQUIRED_HEARTBEAT_INTERVAL = Duration.ofSeconds(5);
    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(15);
    public static final int DEFAULT_REAPER_BATCH_SIZE = 100;

    public LeasePolicy {
        Objects.requireNonNull(heartbeatInterval, "heartbeatInterval must not be null");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (!heartbeatInterval.equals(REQUIRED_HEARTBEAT_INTERVAL)) {
            throw new IllegalArgumentException("Worker 心跳周期必须为 5 秒");
        }
        if (leaseDuration.compareTo(heartbeatInterval) <= 0) {
            throw new IllegalArgumentException("leaseDuration 必须大于 heartbeatInterval");
        }
        if (reaperBatchSize < 1) {
            throw new IllegalArgumentException("reaperBatchSize 必须大于 0");
        }
    }

    public static LeasePolicy defaults() {
        return new LeasePolicy(
                REQUIRED_HEARTBEAT_INTERVAL,
                DEFAULT_LEASE_DURATION,
                DEFAULT_REAPER_BATCH_SIZE
        );
    }
}
