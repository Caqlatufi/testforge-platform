package io.testforge.workergateway.device.lease;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** MySQL 中 DeviceSlot 租约的一致性快照。 */
public record DeviceLeaseSnapshot(
        UUID deviceSlotId,
        DeviceLeaseState state,
        UUID currentAttemptId,
        UUID leaseToken,
        Instant leaseUntil,
        Instant reservedAt,
        Instant lastHeartbeatAt,
        Instant releasedAt,
        DeviceLeaseReleaseReason releaseReason,
        long version
) {
    public DeviceLeaseSnapshot {
        Objects.requireNonNull(deviceSlotId, "deviceSlotId 不能为空");
        Objects.requireNonNull(state, "state 不能为空");
        if (version < 0) {
            throw new IllegalArgumentException("version 不能小于 0");
        }
        boolean completeReservation = currentAttemptId != null
                && leaseToken != null
                && leaseUntil != null
                && reservedAt != null
                && lastHeartbeatAt != null;
        if (state == DeviceLeaseState.RESERVED && !completeReservation) {
            throw new IllegalArgumentException("RESERVED 必须包含完整 Attempt 租约");
        }
        if (state == DeviceLeaseState.AVAILABLE
                && (currentAttemptId != null || leaseToken != null || leaseUntil != null)) {
            throw new IllegalArgumentException("AVAILABLE 不能绑定 Attempt 租约");
        }
    }

    public boolean isReservedBy(UUID attemptId) {
        return state == DeviceLeaseState.RESERVED
                && Objects.equals(currentAttemptId, attemptId);
    }
}
