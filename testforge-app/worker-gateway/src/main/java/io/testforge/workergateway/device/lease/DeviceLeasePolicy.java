package io.testforge.workergateway.device.lease;

import java.time.Duration;
import java.util.Objects;

/** DeviceSlot 租约时间窗口及单轮回收上限。 */
public record DeviceLeasePolicy(Duration leaseDuration, int reaperBatchSize) {

    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(15);
    public static final int DEFAULT_REAPER_BATCH_SIZE = 100;

    public DeviceLeasePolicy {
        Objects.requireNonNull(leaseDuration, "leaseDuration 不能为空");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration 必须大于 0");
        }
        if (reaperBatchSize < 1) {
            throw new IllegalArgumentException("reaperBatchSize 必须大于 0");
        }
    }

    public static DeviceLeasePolicy defaults() {
        return new DeviceLeasePolicy(DEFAULT_LEASE_DURATION, DEFAULT_REAPER_BATCH_SIZE);
    }
}
