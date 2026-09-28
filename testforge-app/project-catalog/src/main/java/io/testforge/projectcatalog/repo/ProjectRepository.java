package io.testforge.projectcatalog.repo;

import io.testforge.projectcatalog.entity.TestProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<TestProjectEntity, UUID> {

    Optional<TestProjectEntity> findByCode(String code);
    List<TestProjectEntity> findAllByOrderByCreatedAtAsc();
}
