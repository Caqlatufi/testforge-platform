package io.testforge.cicdgateway.deployment.repo;

import io.testforge.cicdgateway.deployment.entity.DeploymentProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentProfileRepository extends JpaRepository<DeploymentProfileEntity, UUID> {
    Optional<DeploymentProfileEntity> findByTargetIdAndName(UUID targetId, String name);
    Optional<DeploymentProfileEntity> findByTargetIdAndJobName(UUID targetId, String jobName);
    List<DeploymentProfileEntity> findAllByTargetIdOrderByCreatedAtAsc(UUID targetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select profile from DeploymentProfileEntity profile where profile.id = :id")
    Optional<DeploymentProfileEntity> findByIdForUpdate(@Param("id") UUID id);
}
