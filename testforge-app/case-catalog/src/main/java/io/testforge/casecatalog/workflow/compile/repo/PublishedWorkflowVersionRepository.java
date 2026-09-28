package io.testforge.casecatalog.workflow.compile.repo;

import io.testforge.casecatalog.workflow.compile.entity.PublishedWorkflowVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PublishedWorkflowVersionRepository
        extends JpaRepository<PublishedWorkflowVersionEntity, UUID> {

    Optional<PublishedWorkflowVersionEntity> findByWorkflowIdAndVersion(UUID workflowId, int version);

    Optional<PublishedWorkflowVersionEntity> findByWorkflowIdAndRequestKey(UUID workflowId, UUID requestKey);

    List<PublishedWorkflowVersionEntity> findAllByWorkflowIdOrderByVersionAsc(UUID workflowId);
}
