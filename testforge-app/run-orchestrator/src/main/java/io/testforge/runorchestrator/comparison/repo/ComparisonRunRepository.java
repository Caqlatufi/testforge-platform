package io.testforge.runorchestrator.comparison.repo;

import io.testforge.runorchestrator.comparison.entity.ComparisonRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ComparisonRunRepository extends JpaRepository<ComparisonRunEntity, UUID> {
    Optional<ComparisonRunEntity> findByRequestKey(UUID requestKey);
}
