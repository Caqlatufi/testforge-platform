package io.testforge.workergateway.registry.repo;

import io.testforge.workergateway.registry.entity.WorkerNodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerNodeRepository extends JpaRepository<WorkerNodeEntity, UUID> {

    Optional<WorkerNodeEntity> findByWorkerId(String workerId);

    List<WorkerNodeEntity> findAllByOrderByWorkerIdAsc();
}
