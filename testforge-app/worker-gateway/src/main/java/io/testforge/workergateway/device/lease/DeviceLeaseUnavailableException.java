package io.testforge.workergateway.device.lease;

import java.util.UUID;

/** 槽位已被其他 Attempt 占用，或同一 Attempt 已绑定其他槽位。 */
public class DeviceLeaseUnavailableException extends RuntimeException {

    public DeviceLeaseUnavailableException(UUID deviceSlotId, UUID attemptId) {
        super("DeviceSlot 当前不可占用: deviceSlotId=" + deviceSlotId + ", attemptId=" + attemptId);
    }
}
