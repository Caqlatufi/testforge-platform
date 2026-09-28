package io.testforge.cicdgateway.deployment.repo;

import io.testforge.cicdgateway.deployment.entity.TargetDeploymentEntity;
import io.testforge.cicdgateway.deployment.model.DeploymentState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface TargetDeploymentRepository extends JpaRepository<TargetDeploymentEntity, UUID> {
    Optional<TargetDeploymentEntity> findByDeploymentKey(String deploymentKey);
    long countByProfileIdAndStateIn(UUID profileId, Collection<DeploymentState> states);
    long countByTargetIdAndEnvironmentExternalIdAndStateIn(
            UUID targetId, String environmentExternalId, Collection<DeploymentState> states);

    long countByEnvironmentExternalIdAndStateIn(
            String environmentExternalId, Collection<DeploymentState> states);
    List<TargetDeploymentEntity> findAllByTargetIdOrderByCreatedAtDesc(UUID targetId);
    List<TargetDeploymentEntity> findAllByStateAndExpiresAtLessThanEqual(
            DeploymentState state,
            Instant expiresAt
    );
    List<TargetDeploymentEntity> findAllByStateInAndUpdatedAtLessThanEqual(
            Collection<DeploymentState> states,
            Instant updatedAt
    );
}
