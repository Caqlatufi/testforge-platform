package io.testforge.workergateway.device.lease;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 一轮过期 DeviceSlot 回收的可审计结果。 */
public record DeviceLeaseReapResult(
        Instant scannedAt,
        int candidateCount,
        List<UUID> releasedDeviceSlotIds
) {
    public DeviceLeaseReapResult {
        Objects.requireNonNull(scannedAt, "scannedAt 不能为空");
        if (candidateCount < 0) {
            throw new IllegalArgumentException("candidateCount 不能小于 0");
        }
        releasedDeviceSlotIds = List.copyOf(Objects.requireNonNull(
                releasedDeviceSlotIds, "releasedDeviceSlotIds 不能为空"
        ));
        if (releasedDeviceSlotIds.size() > candidateCount) {
            throw new IllegalArgumentException("释放数量不能超过候选数量");
        }
    }

    public int releasedCount() {
        return releasedDeviceSlotIds.size();
    }
}
