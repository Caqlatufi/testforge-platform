package io.testforge.casecatalog.workflow.draft;

import java.util.UUID;

/**
 * Workflow 草稿中的有向依赖边。
 */
public record WorkflowDraftEdge(
        UUID predecessorNodeId,
        UUID successorNodeId,
        WorkflowEdgeCondition condition
) {
}
