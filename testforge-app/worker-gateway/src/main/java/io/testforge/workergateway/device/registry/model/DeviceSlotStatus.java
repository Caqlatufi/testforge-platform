package io.testforge.workergateway.device.registry.model;

/**
 * 对外设备视图同时表达在线状态和租约占用状态。
 */
public enum DeviceSlotStatus {
    AVAILABLE,
    RESERVED,
    OFFLINE
}
