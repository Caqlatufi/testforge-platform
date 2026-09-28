package io.testforge.observability.event;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionEventRepository extends JpaRepository<ExecutionEventEntity, Long> {
    Optional<ExecutionEventEntity> findByEventKey(UUID eventKey);
    List<ExecutionEventEntity> findByRunIdAndIdGreaterThanOrderByIdAsc(
            UUID runId, long afterEventId, Pageable pageable
    );
}
