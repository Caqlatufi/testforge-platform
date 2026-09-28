package io.testforge.runorchestrator.task.repo;

import io.testforge.runorchestrator.task.entity.TaskDependencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TaskDependencyRepository extends JpaRepository<TaskDependencyEntity, UUID> {

    List<TaskDependencyEntity> findAllByRunIdOrderBySuccessorTaskIdAscPredecessorTaskIdAsc(UUID runId);

    long countByRunId(UUID runId);
}
