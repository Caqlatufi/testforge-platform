package io.testforge.projectcatalog.repo;

import io.testforge.projectcatalog.entity.TestEnvironmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectEnvironmentRepository extends JpaRepository<TestEnvironmentEntity, UUID> {

    Optional<TestEnvironmentEntity> findByTargetIdAndName(UUID targetId, String name);

    Optional<TestEnvironmentEntity> findByTargetIdAndProviderEnvironmentKey(UUID targetId, String providerEnvironmentKey);

    Optional<TestEnvironmentEntity> findByName(String name);

    Optional<TestEnvironmentEntity> findByProviderEnvironmentKey(String providerEnvironmentKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select environment from TestEnvironmentEntity environment where environment.providerEnvironmentKey = :providerKey")
    Optional<TestEnvironmentEntity> findForDeploymentUpdate(
            @Param("providerKey") String providerKey);

    List<TestEnvironmentEntity> findAllByProjectIdOrderByCreatedAtAsc(UUID projectId);

    List<TestEnvironmentEntity> findAllByResourcePoolKeyOrderByCreatedAtAsc(String resourcePoolKey);

    List<TestEnvironmentEntity> findAllByOrderByCreatedAtAsc();
}
