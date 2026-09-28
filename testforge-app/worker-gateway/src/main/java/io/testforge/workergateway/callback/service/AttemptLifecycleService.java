package io.testforge.workergateway.callback.service;

import io.testforge.workergateway.callback.model.AttemptHeartbeatRequest;
import io.testforge.workergateway.callback.model.AttemptLeaseResponse;
import io.testforge.workergateway.callback.model.AttemptStartRequest;
import io.testforge.workergateway.device.lease.DeviceLeaseService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class AttemptLifecycleService {

    private final AttemptExecutionGateway executionGateway;
    private final Clock clock;
    private final Duration leaseDuration;
    private final DeviceLeaseService deviceLeaseService;

    public AttemptLifecycleService(
            AttemptExecutionGateway executionGateway,
            Clock clock,
            Duration leaseDuration
    ) {
        this(executionGateway, clock, leaseDuration, null);
    }

    public AttemptLifecycleService(
            AttemptExecutionGateway executionGateway,
            Clock clock,
            Duration leaseDuration,
            DeviceLeaseService deviceLeaseService
    ) {
        this.executionGateway = Objects.requireNonNull(executionGateway, "executionGateway 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration 不能为空");
        this.deviceLeaseService = deviceLeaseService;
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration 必须大于 0");
        }
    }

    public AttemptLeaseResponse start(UUID attemptId, AttemptStartRequest request) {
        return renew(attemptId, request.workerId(), request.leaseToken(), null);
    }

    public AttemptLeaseResponse heartbeat(UUID attemptId, AttemptHeartbeatRequest request) {
        return renew(attemptId, request.workerId(), request.leaseToken(), request.progress());
    }

    private AttemptLeaseResponse renew(
            UUID attemptId,
            String workerId,
            UUID leaseToken,
            BigDecimal progress
    ) {
        Instant acceptedAt = clock.instant();
        Instant extendedUntil = acceptedAt.plus(leaseDuration);
        AttemptLeaseSnapshot lease = executionGateway.renew(
                        attemptId,
                        workerId,
                        leaseToken,
                        acceptedAt,
                        extendedUntil
                )
                .orElseThrow(() -> new AttemptLeaseExpiredException(attemptId));
        if (deviceLeaseService != null) {
            deviceLeaseService.heartbeatForAttempt(attemptId);
        }
        return new AttemptLeaseResponse(
                lease.attemptId(),
                "RUNNING",
                lease.leaseUntil(),
                progress,
                acceptedAt
        );
    }
}
