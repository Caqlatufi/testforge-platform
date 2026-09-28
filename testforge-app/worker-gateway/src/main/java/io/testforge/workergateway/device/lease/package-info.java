/**
 * DeviceSlot 的 MySQL 独占租约、Attempt 绑定、心跳与异常释放。
 *
 * <p>本包只接收已经选定的 deviceSlotId，不实现 runner、platform 或 features 能力匹配。</p>
 */
package io.testforge.workergateway.device.lease;
