package io.testforge.runorchestrator.run.model;

import java.util.UUID;

/** 报告写入时需要的稳定 Task 归属，不暴露 run-orchestrator 仓储。 */
public record TaskHistoryContext(UUID runId, UUID taskId, UUID caseId) {
}
