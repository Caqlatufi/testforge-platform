package io.testforge.casecatalog.testcase.repo;

import io.testforge.casecatalog.testcase.entity.TestCaseEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import io.testforge.casecatalog.testcase.model.CaseScope;

public interface TestCaseRepository extends JpaRepository<TestCaseEntity, UUID> {

    Optional<TestCaseEntity> findByProjectIdAndTargetIdAndName(UUID projectId, UUID targetId, String name);

    List<TestCaseEntity> findAllByProjectIdOrderByCreatedAtAsc(UUID projectId);

    List<TestCaseEntity> findAllByScopeOrderByCreatedAtAsc(CaseScope scope);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select testCase from TestCaseEntity testCase where testCase.id = :id")
    Optional<TestCaseEntity> findByIdForUpdate(@Param("id") UUID id);
}
