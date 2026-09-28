package io.testforge.casecatalog.workflow.repo;

import io.testforge.casecatalog.workflow.entity.TestWorkflowEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface TestWorkflowRepository extends JpaRepository<TestWorkflowEntity, UUID> {

    Optional<TestWorkflowEntity> findByProjectIdAndTargetIdAndName(UUID projectId, UUID targetId, String name);
    List<TestWorkflowEntity> findAllByProjectIdOrderByCreatedAtAsc(UUID projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select workflow from TestWorkflowEntity workflow where workflow.id = :id")
    Optional<TestWorkflowEntity> findByIdForUpdate(@Param("id") UUID id);
}
