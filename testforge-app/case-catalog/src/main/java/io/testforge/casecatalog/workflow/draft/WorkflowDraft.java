package io.testforge.casecatalog.workflow.draft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 尚未发布、允许编辑的 Workflow 图快照。
 */
public record WorkflowDraft(
        UUID workflowId,
        List<WorkflowDraftNode> nodes,
        List<WorkflowDraftEdge> edges
) {

    public WorkflowDraft {
        Objects.requireNonNull(workflowId, "workflowId 不能为空");
        Objects.requireNonNull(nodes, "nodes 不能为空");
        Objects.requireNonNull(edges, "edges 不能为空");
        nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
        edges = Collections.unmodifiableList(new ArrayList<>(edges));
    }
}
