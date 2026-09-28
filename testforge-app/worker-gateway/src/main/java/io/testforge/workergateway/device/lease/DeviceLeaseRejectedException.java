package io.testforge.workergateway.device.lease;

import java.util.UUID;

/** Attempt、token、状态或租约时间任一条件失效时拒绝续租。 */
public class DeviceLeaseRejectedException extends RuntimeException {

    public DeviceLeaseRejectedException(UUID deviceSlotId, UUID attemptId) {
        super("DeviceSlot 租约已失效或已被替代: deviceSlotId=" + deviceSlotId
                + ", attemptId=" + attemptId);
    }
}
