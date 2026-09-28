package io.testforge.dispatcher.outbox.repo;

import io.testforge.dispatcher.outbox.entity.OutboxEventEntity;
import io.testforge.dispatcher.outbox.model.OutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    Optional<OutboxEventEntity> findByEventKey(String eventKey);

    long countByStatus(OutboxStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select event from OutboxEventEntity event
            where (event.status = io.testforge.dispatcher.outbox.model.OutboxStatus.PENDING
                    and event.availableAt <= :now)
               or (event.status = io.testforge.dispatcher.outbox.model.OutboxStatus.CLAIMED
                    and event.leaseUntil <= :now)
            order by event.availableAt asc, event.createdAt asc, event.id asc
            """)
    List<OutboxEventEntity> findClaimableForUpdate(@Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from OutboxEventEntity event where event.id = :id")
    Optional<OutboxEventEntity> findByIdForUpdate(@Param("id") UUID id);
}
