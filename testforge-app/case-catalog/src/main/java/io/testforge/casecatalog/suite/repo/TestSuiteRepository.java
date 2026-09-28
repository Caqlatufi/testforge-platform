package io.testforge.casecatalog.suite.repo;

import io.testforge.casecatalog.suite.entity.TestSuiteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestSuiteRepository extends JpaRepository<TestSuiteEntity, UUID> {

    Optional<TestSuiteEntity> findByProjectIdAndTargetIdAndName(UUID projectId, UUID targetId, String name);

    List<TestSuiteEntity> findAllByProjectIdAndTargetIdOrderByNameAsc(UUID projectId, UUID targetId);
}
