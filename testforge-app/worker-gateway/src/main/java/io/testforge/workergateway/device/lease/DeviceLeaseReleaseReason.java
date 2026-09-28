package io.testforge.workergateway.device.lease;

/** 释放原因会持久化，便于解释取消、正常终态和失联回收。 */
public enum DeviceLeaseReleaseReason {
    CANCELLED,
    TERMINAL,
    EXPIRED
}
