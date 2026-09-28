package io.testforge.casecatalog.workflow.draft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * 对 Workflow 草稿执行权威结构校验和拓扑排序。
 *
 * <p>校验器无状态且不依赖 Spring、数据库或其他子域，可在保存草稿和发布前复用。</p>
 */
public final class WorkflowDraftGraphValidator {

    static final int MAX_TIMEOUT_SECONDS = 86_400;

    public ValidatedWorkflowDraft validate(WorkflowDraft draft) {
        Objects.requireNonNull(draft, "draft 不能为空");

        List<WorkflowDraftViolation> violations = new ArrayList<>();
        if (draft.nodes().isEmpty()) {
            violations.add(violation(
                    WorkflowDraftViolationCode.EMPTY_GRAPH,
                    "Workflow 草稿至少需要一个节点"
            ));
        }

        Map<UUID, WorkflowDraftNode> nodesById = validateNodes(draft.nodes(), violations);
        validateEdges(draft.edges(), nodesById, violations);
        if (!violations.isEmpty()) {
            throw new WorkflowDraftValidationException(violations);
        }

        Topology topology = topologicalSort(draft.nodes(), draft.edges(), nodesById);
        if (topology.sortedNodes().size() != draft.nodes().size()) {
            List<UUID> cycle = findCycle(draft.nodes(), topology.adjacency());
            String cycleText = cycle.isEmpty()
                    ? "无法定位环路"
                    : cycle.stream().map(UUID::toString).reduce((left, right) -> left + " -> " + right).orElseThrow();
            throw new WorkflowDraftValidationException(List.of(violation(
                    WorkflowDraftViolationCode.CYCLE,
                    "Workflow 草稿必须是 DAG，检测到环路: " + cycleText
            )));
        }

        return new ValidatedWorkflowDraft(draft, topology.sortedNodes());
    }

    public List<WorkflowDraftNode> topologicalSort(WorkflowDraft draft) {
        return validate(draft).topologicalNodes();
    }

    private Map<UUID, WorkflowDraftNode> validateNodes(
            List<WorkflowDraftNode> nodes,
            List<WorkflowDraftViolation> violations
    ) {
        Map<UUID, WorkflowDraftNode> nodesById = new LinkedHashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            WorkflowDraftNode node = nodes.get(index);
            if (node == null) {
                violations.add(violation(
                        WorkflowDraftViolationCode.NULL_NODE,
                        "第 " + index + " 个 Workflow 节点不能为空"
                ));
                continue;
            }

            if (node.id() == null) {
                violations.add(violation(
                        WorkflowDraftViolationCode.MISSING_NODE_ID,
                        "第 " + index + " 个 Workflow 节点缺少 id"
                ));
            } else if (nodesById.putIfAbsent(node.id(), node) != null) {
                violations.add(violation(
                        WorkflowDraftViolationCode.DUPLICATE_NODE_ID,
                        "Workflow 节点 id 重复: " + node.id()
                ));
            }

            String nodeLabel = node.id() == null ? "索引 " + index : node.id().toString();
            if (node.type() == null) {
                violations.add(violation(
                        WorkflowDraftViolationCode.MISSING_NODE_TYPE,
                        "Workflow 节点缺少类型: " + nodeLabel
                ));
            }
            if (node.referenceId() == null) {
                violations.add(violation(
                        WorkflowDraftViolationCode.MISSING_REFERENCE_ID,
                        "Workflow 节点缺少引用 id: " + nodeLabel
                ));
            }
            if (node.referenceVersion() == null || node.referenceVersion() < 1) {
                violations.add(violation(
                        WorkflowDraftViolationCode.INVALID_REFERENCE_VERSION,
                        "Workflow 节点引用版本必须大于等于 1: " + nodeLabel
                ));
            }
            if (node.required() == null) {
                violations.add(violation(
                        WorkflowDraftViolationCode.MISSING_REQUIRED,
                        "Workflow 节点必须明确 required: " + nodeLabel
                ));
            }
            if (node.timeoutSeconds() != null
                    && (node.timeoutSeconds() < 1 || node.timeoutSeconds() > MAX_TIMEOUT_SECONDS)) {
                violations.add(violation(
                        WorkflowDraftViolationCode.INVALID_TIMEOUT,
                        "Workflow 节点 timeoutSeconds 必须在 1 到 86400 之间: " + nodeLabel
                ));
            }
            if (!Double.isFinite(node.positionX()) || !Double.isFinite(node.positionY())) {
                violations.add(violation(
                        WorkflowDraftViolationCode.INVALID_POSITION,
                        "Workflow 节点画布坐标必须是有限数值: " + nodeLabel
                ));
            }
        }
        return nodesById;
    }

    private void validateEdges(
            List<WorkflowDraftEdge> edges,
            Map<UUID, WorkflowDraftNode> nodesById,
            List<WorkflowDraftViolation> violations
    ) {
        Set<EdgeEndpoints> endpointPairs = new HashSet<>();
        for (int index = 0; index < edges.size(); index++) {
            WorkflowDraftEdge edge = edges.get(index);
            if (edge == null) {
                violations.add(violation(
                        WorkflowDraftViolationCode.NULL_EDGE,
                        "第 " + index + " 条 Workflow 边不能为空"
                ));
                continue;
            }

            UUID predecessorId = edge.predecessorNodeId();
            UUID successorId = edge.successorNodeId();
            if (predecessorId == null || successorId == null) {
                violations.add(violation(
                        WorkflowDraftViolationCode.MISSING_EDGE_ENDPOINT,
                        "第 " + index + " 条 Workflow 边缺少前置或后继节点 id"
                ));
            } else {
                EdgeEndpoints endpoints = new EdgeEndpoints(predecessorId, successorId);
                if (!endpointPairs.add(endpoints)) {
                    violations.add(violation(
                            WorkflowDraftViolationCode.DUPLICATE_EDGE,
                            "Workflow 边重复: " + predecessorId + " -> " + successorId
                    ));
                }
                if (predecessorId.equals(successorId)) {
                    violations.add(violation(
                            WorkflowDraftViolationCode.SELF_DEPENDENCY,
                            "Workflow 节点不能依赖自身: " + predecessorId
                    ));
                }
                if (!nodesById.containsKey(predecessorId)) {
                    violations.add(violation(
                            WorkflowDraftViolationCode.DANGLING_EDGE,
                            "Workflow 边引用不存在的前置节点: " + predecessorId
                    ));
                }
                if (!nodesById.containsKey(successorId)) {
                    violations.add(violation(
                            WorkflowDraftViolationCode.DANGLING_EDGE,
                            "Workflow 边引用不存在的后继节点: " + successorId
                    ));
                }
            }

            if (edge.condition() == null) {
                violations.add(violation(
                        WorkflowDraftViolationCode.MISSING_EDGE_CONDITION,
                        "第 " + index + " 条 Workflow 边必须明确依赖条件"
                ));
            }
        }
    }

    private Topology topologicalSort(
            List<WorkflowDraftNode> nodes,
            List<WorkflowDraftEdge> edges,
            Map<UUID, WorkflowDraftNode> nodesById
    ) {
        Map<UUID, Integer> nodeOrder = new HashMap<>();
        Map<UUID, Integer> indegree = new LinkedHashMap<>();
        Map<UUID, List<UUID>> adjacency = new LinkedHashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            UUID nodeId = nodes.get(index).id();
            nodeOrder.put(nodeId, index);
            indegree.put(nodeId, 0);
            adjacency.put(nodeId, new ArrayList<>());
        }
        for (WorkflowDraftEdge edge : edges) {
            adjacency.get(edge.predecessorNodeId()).add(edge.successorNodeId());
            indegree.compute(edge.successorNodeId(), (ignored, value) -> value + 1);
        }

        Comparator<UUID> byDraftOrder = Comparator.comparingInt(nodeOrder::get);
        adjacency.values().forEach(successors -> successors.sort(byDraftOrder));
        PriorityQueue<UUID> ready = new PriorityQueue<>(byDraftOrder);
        indegree.forEach((nodeId, degree) -> {
            if (degree == 0) {
                ready.add(nodeId);
            }
        });

        List<WorkflowDraftNode> sorted = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            UUID nodeId = ready.remove();
            sorted.add(nodesById.get(nodeId));
            for (UUID successorId : adjacency.get(nodeId)) {
                int remaining = indegree.compute(successorId, (ignored, value) -> value - 1);
                if (remaining == 0) {
                    ready.add(successorId);
                }
            }
        }
        return new Topology(List.copyOf(sorted), adjacency);
    }

    private List<UUID> findCycle(
            List<WorkflowDraftNode> nodes,
            Map<UUID, List<UUID>> adjacency
    ) {
        Map<UUID, VisitState> states = new HashMap<>();
        List<UUID> path = new ArrayList<>();
        Map<UUID, Integer> pathIndexes = new HashMap<>();
        for (WorkflowDraftNode node : nodes) {
            if (states.getOrDefault(node.id(), VisitState.UNVISITED) == VisitState.UNVISITED) {
                List<UUID> cycle = findCycleFrom(node.id(), adjacency, states, path, pathIndexes);
                if (!cycle.isEmpty()) {
                    return cycle;
                }
            }
        }
        return List.of();
    }

    private List<UUID> findCycleFrom(
            UUID nodeId,
            Map<UUID, List<UUID>> adjacency,
            Map<UUID, VisitState> states,
            List<UUID> path,
            Map<UUID, Integer> pathIndexes
    ) {
        states.put(nodeId, VisitState.VISITING);
        pathIndexes.put(nodeId, path.size());
        path.add(nodeId);

        for (UUID successorId : adjacency.get(nodeId)) {
            VisitState successorState = states.getOrDefault(successorId, VisitState.UNVISITED);
            if (successorState == VisitState.VISITING) {
                List<UUID> cycle = new ArrayList<>(path.subList(pathIndexes.get(successorId), path.size()));
                cycle.add(successorId);
                return List.copyOf(cycle);
            }
            if (successorState == VisitState.UNVISITED) {
                List<UUID> cycle = findCycleFrom(successorId, adjacency, states, path, pathIndexes);
                if (!cycle.isEmpty()) {
                    return cycle;
                }
            }
        }

        path.removeLast();
        pathIndexes.remove(nodeId);
        states.put(nodeId, VisitState.VISITED);
        return List.of();
    }

    private WorkflowDraftViolation violation(WorkflowDraftViolationCode code, String message) {
        return new WorkflowDraftViolation(code, message);
    }

    private record EdgeEndpoints(UUID predecessorNodeId, UUID successorNodeId) {
    }

    private record Topology(
            List<WorkflowDraftNode> sortedNodes,
            Map<UUID, List<UUID>> adjacency
    ) {
    }

    private enum VisitState {
        UNVISITED,
        VISITING,
        VISITED
    }
}
