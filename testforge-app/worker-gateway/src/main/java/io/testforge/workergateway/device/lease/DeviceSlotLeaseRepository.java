package io.testforge.workergateway.device.lease;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceSlotLeaseRepository extends JpaRepository<DeviceSlotLeaseEntity, UUID> {

    Optional<DeviceSlotLeaseEntity> findByCurrentAttemptId(UUID currentAttemptId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeviceSlotLeaseEntity lease
               set lease.state = io.testforge.workergateway.device.lease.DeviceLeaseState.RESERVED,
                   lease.currentAttemptId = :attemptId,
                   lease.leaseToken = :leaseToken,
                   lease.leaseUntil = :leaseUntil,
                   lease.reservedAt = :reservedAt,
                   lease.lastHeartbeatAt = :reservedAt,
                   lease.releasedAt = null,
                   lease.releaseReason = null,
                   lease.version = lease.version + 1
             where lease.deviceSlotId = :deviceSlotId
               and lease.state = io.testforge.workergateway.device.lease.DeviceLeaseState.AVAILABLE
            """)
    int reserveIfAvailable(
            @Param("deviceSlotId") UUID deviceSlotId,
            @Param("attemptId") UUID attemptId,
            @Param("leaseToken") UUID leaseToken,
            @Param("reservedAt") Instant reservedAt,
            @Param("leaseUntil") Instant leaseUntil
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeviceSlotLeaseEntity lease
               set lease.leaseUntil = :extendedUntil,
                   lease.lastHeartbeatAt = :acceptedAt,
                   lease.version = lease.version + 1
             where lease.deviceSlotId = :deviceSlotId
               and lease.state = io.testforge.workergateway.device.lease.DeviceLeaseState.RESERVED
               and lease.currentAttemptId = :attemptId
               and lease.leaseToken = :leaseToken
               and lease.leaseUntil >= :acceptedAt
            """)
    int heartbeat(
            @Param("deviceSlotId") UUID deviceSlotId,
            @Param("attemptId") UUID attemptId,
            @Param("leaseToken") UUID leaseToken,
            @Param("acceptedAt") Instant acceptedAt,
            @Param("extendedUntil") Instant extendedUntil
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeviceSlotLeaseEntity lease
               set lease.state = io.testforge.workergateway.device.lease.DeviceLeaseState.AVAILABLE,
                   lease.currentAttemptId = null,
                   lease.leaseToken = null,
                   lease.leaseUntil = null,
                   lease.reservedAt = null,
                   lease.lastHeartbeatAt = null,
                   lease.releasedAt = :releasedAt,
                   lease.releaseReason = :releaseReason,
                   lease.version = lease.version + 1
             where lease.currentAttemptId = :attemptId
               and lease.state = io.testforge.workergateway.device.lease.DeviceLeaseState.RESERVED
            """)
    int releaseByAttempt(
            @Param("attemptId") UUID attemptId,
            @Param("releaseReason") DeviceLeaseReleaseReason releaseReason,
            @Param("releasedAt") Instant releasedAt
    );

    @Query("""
            select lease from DeviceSlotLeaseEntity lease
             where lease.state = io.testforge.workergateway.device.lease.DeviceLeaseState.RESERVED
               and lease.leaseUntil <= :expiredAtOrBefore
             order by lease.leaseUntil asc, lease.deviceSlotId asc
            """)
    List<DeviceSlotLeaseEntity> findExpired(
            @Param("expiredAtOrBefore") Instant expiredAtOrBefore,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeviceSlotLeaseEntity lease
               set lease.state = io.testforge.workergateway.device.lease.DeviceLeaseState.AVAILABLE,
                   lease.currentAttemptId = null,
                   lease.leaseToken = null,
                   lease.leaseUntil = null,
                   lease.reservedAt = null,
                   lease.lastHeartbeatAt = null,
                   lease.releasedAt = :releasedAt,
                   lease.releaseReason = io.testforge.workergateway.device.lease.DeviceLeaseReleaseReason.EXPIRED,
                   lease.version = lease.version + 1
             where lease.deviceSlotId = :deviceSlotId
               and lease.state = io.testforge.workergateway.device.lease.DeviceLeaseState.RESERVED
               and lease.currentAttemptId = :attemptId
               and lease.leaseToken = :leaseToken
               and lease.version = :expectedVersion
               and lease.leaseUntil <= :releasedAt
            """)
    int releaseIfExpired(
            @Param("deviceSlotId") UUID deviceSlotId,
            @Param("attemptId") UUID attemptId,
            @Param("leaseToken") UUID leaseToken,
            @Param("expectedVersion") long expectedVersion,
            @Param("releasedAt") Instant releasedAt
    );
}
