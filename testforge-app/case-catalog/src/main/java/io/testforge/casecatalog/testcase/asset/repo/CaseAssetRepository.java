package io.testforge.casecatalog.testcase.asset.repo;

import io.testforge.casecatalog.testcase.asset.entity.CaseAssetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CaseAssetRepository extends JpaRepository<CaseAssetEntity, UUID> {
    Optional<CaseAssetEntity> findByProjectIdAndSha256(UUID projectId, String sha256);
    List<CaseAssetEntity> findAllByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
