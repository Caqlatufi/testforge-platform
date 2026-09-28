package io.testforge.workergateway.device.lease;

/** DeviceSlot 在租约子域中的占用状态；OFFLINE 由设备注册子域维护。 */
public enum DeviceLeaseState {
    AVAILABLE,
    RESERVED
}
