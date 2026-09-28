package io.testforge.workergateway.device.lease;

import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/** 即使 Worker/Attempt 回调缺失，也会从 MySQL 最终回收过期设备。 */
public class DeviceLeaseReaperScheduler {

    private final DeviceLeaseService service;

    public DeviceLeaseReaperScheduler(DeviceLeaseService service) {
        this.service = Objects.requireNonNull(service, "service 不能为空");
    }

    @Scheduled(fixedDelayString = "${testforge.worker-gateway.device-lease.reaper.fixed-delay:5000}")
    public void reapExpired() {
        service.releaseExpired();
    }
}
