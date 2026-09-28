package io.testforge.runorchestrator.run.model;

import io.testforge.runorchestrator.task.model.TaskView;

import java.util.Map;
import java.util.UUID;

/** Worker 领取任务时所需的稳定执行上下文，不暴露持久化实体。 */
public record TaskExecutionView(
        UUID projectId,
        UUID targetId,
        UUID environmentId,
        String environmentEndpoint,
        Map<String, Object> environmentConfig,
        Map<String, String> secretRefs,
        TaskView task
) {
    public TaskExecutionView {
        environmentConfig = Map.copyOf(environmentConfig);
        secretRefs = Map.copyOf(secretRefs);
    }
}
