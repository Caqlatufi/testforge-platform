package io.testforge.projectcatalog.repo;

import io.testforge.projectcatalog.entity.TestTargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectTargetRepository extends JpaRepository<TestTargetEntity, UUID> {

    Optional<TestTargetEntity> findByProjectIdAndName(UUID projectId, String name);

    List<TestTargetEntity> findAllByProjectIdOrderByCreatedAtAsc(UUID projectId);
}
