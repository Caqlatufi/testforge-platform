package io.testforge.casecatalog.workflow.draft;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 已通过完整结构和 DAG 校验的草稿，以及稳定的拓扑顺序。
 */
public record ValidatedWorkflowDraft(
        WorkflowDraft draft,
        List<WorkflowDraftNode> topologicalNodes
) {

    public ValidatedWorkflowDraft {
        Objects.requireNonNull(draft, "draft 不能为空");
        topologicalNodes = List.copyOf(topologicalNodes);
        if (topologicalNodes.size() != draft.nodes().size()) {
            throw new IllegalArgumentException("拓扑节点数量必须与草稿节点数量一致");
        }
    }

    public List<UUID> topologicalNodeIds() {
        return topologicalNodes.stream().map(WorkflowDraftNode::id).toList();
    }
}
