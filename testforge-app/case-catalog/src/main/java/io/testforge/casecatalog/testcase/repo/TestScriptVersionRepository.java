package io.testforge.casecatalog.testcase.repo;

import io.testforge.casecatalog.testcase.entity.TestScriptVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestScriptVersionRepository extends JpaRepository<TestScriptVersionEntity, UUID> {

    Optional<TestScriptVersionEntity> findTopByCaseIdOrderByVersionDesc(UUID caseId);

    Optional<TestScriptVersionEntity> findByCaseIdAndVersion(UUID caseId, int version);

    List<TestScriptVersionEntity> findAllByCaseIdOrderByVersionAsc(UUID caseId);
}
