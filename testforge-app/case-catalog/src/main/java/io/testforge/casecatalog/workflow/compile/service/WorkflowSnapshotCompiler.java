package io.testforge.casecatalog.workflow.compile.service;

import io.testforge.casecatalog.workflow.compile.model.CaseReferenceInput;
import io.testforge.casecatalog.workflow.compile.model.CompiledEdge;
import io.testforge.casecatalog.workflow.compile.model.CompiledNode;
import io.testforge.casecatalog.workflow.compile.model.CompiledWorkflowSnapshot;
import io.testforge.casecatalog.workflow.compile.model.DependencyCondition;
import io.testforge.casecatalog.workflow.compile.model.ExecutableNodeType;
import io.testforge.casecatalog.workflow.compile.model.PublishNodeType;
import io.testforge.casecatalog.workflow.compile.model.ReferenceCatalogInput;
import io.testforge.casecatalog.workflow.compile.model.ReferenceKey;
import io.testforge.casecatalog.workflow.compile.model.SubflowReferenceInput;
import io.testforge.casecatalog.workflow.compile.model.SuiteReferenceInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowEdgeInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowGraphInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowNodeInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowPublishInput;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 将已完成草稿级校验和引用解析的输入编译为只含 CASE/FIXTURE 叶节点的稳定快照。
 *
 * <p>该编译器不访问草稿包或其他资产仓储。接线层负责把固定版本引用解析为
 * {@link ReferenceCatalogInput}，编译器仍会防御性校验版本、归属、递归和展开后的 DAG。</p>
 */
public final class WorkflowSnapshotCompiler {

    private static final int SNAPSHOT_SCHEMA_VERSION = 1;
    private static final Comparator<UUID> UUID_COMPARATOR = Comparator.comparing(UUID::toString);
    private static final Comparator<CompiledEdge> EDGE_COMPARATOR = Comparator
            .comparing((CompiledEdge edge) -> edge.predecessorNodeId().toString())
            .thenComparing(edge -> edge.successorNodeId().toString())
            .thenComparing(edge -> edge.condition().name());

    public CompiledWorkflowSnapshot compile(WorkflowPublishInput input) {
        validatePublishInput(input);
        var rootKey = new ReferenceKey(input.workflowId(), input.version());
        var stack = new ArrayDeque<ReferenceKey>();
        stack.addLast(rootKey);
        var context = new CompileContext(
                input.projectId(),
                input.targetId(),
                input.references(),
                stack
        );
        String rootPath = "workflow:" + input.workflowId() + "@" + input.version();
        Expansion expansion = expandGraph(
                input.graph(),
                rootPath,
                true,
                Map.of(),
                null,
                context
        );
        if (expansion.nodes().isEmpty()) {
            throw new WorkflowCompileException("Workflow 编译结果至少需要一个可执行叶节点");
        }

        List<UUID> topologicalOrder = topologicalOrder(expansion.nodes(), expansion.edges());
        Map<UUID, CompiledNode> nodesById = expansion.nodes();
        List<CompiledNode> orderedNodes = topologicalOrder.stream().map(nodesById::get).toList();
        List<CompiledEdge> orderedEdges = expansion.edges().stream().sorted(EDGE_COMPARATOR).toList();
        return new CompiledWorkflowSnapshot(
                SNAPSHOT_SCHEMA_VERSION,
                input.workflowId(),
                input.version(),
                input.projectId(),
                input.targetId(),
                orderedNodes,
                orderedEdges,
                topologicalOrder
        );
    }

    private Expansion expandGraph(
            WorkflowGraphInput graph,
            String pathPrefix,
            boolean inheritedRequired,
            Map<String, Object> inheritedParameters,
            Integer inheritedTimeout,
            CompileContext context
    ) {
        if (graph == null || graph.nodes().isEmpty()) {
            throw new WorkflowCompileException("Workflow 图至少需要一个节点");
        }

        Map<UUID, WorkflowNodeInput> graphNodes = normalizedNodes(graph.nodes());
        Map<UUID, Expansion> expandedNodes = new LinkedHashMap<>();
        for (WorkflowNodeInput node : graphNodes.values()) {
            expandedNodes.put(node.id(), expandNode(
                    node,
                    pathPrefix,
                    inheritedRequired,
                    inheritedParameters,
                    inheritedTimeout,
                    context
            ));
        }

        Map<UUID, Integer> incoming = new HashMap<>();
        Map<UUID, Integer> outgoing = new HashMap<>();
        graphNodes.keySet().forEach(nodeId -> {
            incoming.put(nodeId, 0);
            outgoing.put(nodeId, 0);
        });

        Map<UUID, CompiledNode> compiledNodes = new LinkedHashMap<>();
        Set<CompiledEdge> compiledEdges = new LinkedHashSet<>();
        expandedNodes.values().forEach(expansion -> merge(expansion, compiledNodes, compiledEdges));

        Set<String> originalEdges = new HashSet<>();
        for (WorkflowEdgeInput edge : graph.edges()) {
            if (edge == null || edge.predecessorNodeId() == null || edge.successorNodeId() == null
                    || edge.condition() == null) {
                throw new WorkflowCompileException("Workflow 边字段不能为空");
            }
            if (!graphNodes.containsKey(edge.predecessorNodeId())
                    || !graphNodes.containsKey(edge.successorNodeId())) {
                throw new WorkflowCompileException("Workflow 边不能引用不存在的节点");
            }
            if (edge.predecessorNodeId().equals(edge.successorNodeId())) {
                throw new WorkflowCompileException("Workflow 节点不能依赖自身: " + edge.predecessorNodeId());
            }
            String edgeKey = edge.predecessorNodeId() + "->" + edge.successorNodeId();
            if (!originalEdges.add(edgeKey)) {
                throw new WorkflowCompileException("Workflow 存在重复边: " + edgeKey);
            }
            incoming.compute(edge.successorNodeId(), (ignored, count) -> count + 1);
            outgoing.compute(edge.predecessorNodeId(), (ignored, count) -> count + 1);

            Expansion predecessor = expandedNodes.get(edge.predecessorNodeId());
            Expansion successor = expandedNodes.get(edge.successorNodeId());
            for (UUID predecessorExit : predecessor.exitNodeIds()) {
                for (UUID successorEntry : successor.entryNodeIds()) {
                    compiledEdges.add(new CompiledEdge(predecessorExit, successorEntry, edge.condition()));
                }
            }
        }

        Set<UUID> entryNodeIds = new LinkedHashSet<>();
        Set<UUID> exitNodeIds = new LinkedHashSet<>();
        graphNodes.keySet().stream().sorted(UUID_COMPARATOR).forEach(nodeId -> {
            if (incoming.get(nodeId) == 0) {
                entryNodeIds.addAll(expandedNodes.get(nodeId).entryNodeIds());
            }
            if (outgoing.get(nodeId) == 0) {
                exitNodeIds.addAll(expandedNodes.get(nodeId).exitNodeIds());
            }
        });

        // 子图本身也必须是 DAG；这样可在递归返回点给出更接近来源的错误。
        topologicalOrder(compiledNodes, compiledEdges);
        return new Expansion(compiledNodes, compiledEdges, entryNodeIds, exitNodeIds);
    }

    private Expansion expandNode(
            WorkflowNodeInput node,
            String pathPrefix,
            boolean inheritedRequired,
            Map<String, Object> inheritedParameters,
            Integer inheritedTimeout,
            CompileContext context
    ) {
        validateNode(node);
        boolean required = inheritedRequired && node.required();
        Map<String, Object> nodeParameters = mergeParameters(inheritedParameters, node.parameterOverrides());
        Integer timeout = node.timeoutSeconds() == null ? inheritedTimeout : node.timeoutSeconds();
        String nodePath = pathPrefix + "/node:" + node.id();
        ReferenceKey key = new ReferenceKey(node.referenceId(), node.referenceVersion());

        return switch (node.type()) {
            case CASE, FIXTURE -> {
                CaseReferenceInput reference = requireCaseReference(key, context.references());
                if (!reference.shared()) {
                    validateOwnership(reference.projectId(), reference.targetId(), key, context);
                }
                ExecutableNodeType expected = node.type() == PublishNodeType.CASE
                        ? ExecutableNodeType.CASE
                        : ExecutableNodeType.FIXTURE;
                if (reference.kind() != expected) {
                    throw new WorkflowCompileException(
                            node.type() + " 节点引用了错误类型的 Case: " + reference.caseId()
                    );
                }
                yield leafExpansion(
                        node,
                        reference,
                        nodePath + "/case:" + reference.caseId() + "@" + reference.scriptVersion(),
                        required,
                        nodeParameters,
                        timeout
                );
            }
            case SUITE -> expandSuite(node, key, nodePath, required, nodeParameters, timeout, context);
            case SUBFLOW -> expandSubflow(node, key, nodePath, required, nodeParameters, timeout, context);
        };
    }

    private Expansion expandSuite(
            WorkflowNodeInput node,
            ReferenceKey key,
            String nodePath,
            boolean required,
            Map<String, Object> nodeParameters,
            Integer timeout,
            CompileContext context
    ) {
        SuiteReferenceInput suite = context.references().suites().get(key);
        if (suite == null) {
            throw new WorkflowCompileException("找不到固定版本 Suite 引用: " + display(key));
        }
        if (!key.equals(suite.key())) {
            throw new WorkflowCompileException("Suite 引用目录键与内容版本不一致: " + display(key));
        }
        validateOwnership(suite.projectId(), suite.targetId(), key, context);
        if (suite.members().isEmpty()) {
            throw new WorkflowCompileException("Suite 固定版本没有成员: " + display(key));
        }

        Map<UUID, CompiledNode> nodes = new LinkedHashMap<>();
        Set<CompiledEdge> edges = new LinkedHashSet<>();
        Set<UUID> boundary = new LinkedHashSet<>();
        Set<ReferenceKey> memberKeys = new HashSet<>();
        if (suite.members().stream().anyMatch(member -> member == null || member.caseId() == null)) {
            throw new WorkflowCompileException("Suite 成员引用及 Case ID 不能为空: " + display(key));
        }
        List<CaseReferenceInput> members = suite.members().stream()
                .sorted(Comparator.comparing((CaseReferenceInput member) -> member.caseId().toString())
                        .thenComparingInt(CaseReferenceInput::scriptVersion))
                .toList();
        Map<String, Object> suiteParameters = mergeParameters(suite.parameterBindings(), nodeParameters);
        for (CaseReferenceInput member : members) {
            if (!memberKeys.add(member.key())) {
                throw new WorkflowCompileException("Suite 固定版本包含重复成员: " + display(member.key()));
            }
            validateCaseReference(member);
            validateOwnership(member.projectId(), member.targetId(), member.key(), context);
            String leafPath = nodePath + "/suite:" + suite.suiteId() + "@" + suite.version()
                    + "/case:" + member.caseId() + "@" + member.scriptVersion();
            Expansion memberExpansion = leafExpansion(
                    node,
                    member,
                    leafPath,
                    required,
                    suiteParameters,
                    timeout
            );
            merge(memberExpansion, nodes, edges);
            boundary.addAll(memberExpansion.entryNodeIds());
        }
        return new Expansion(nodes, edges, boundary, boundary);
    }

    private Expansion expandSubflow(
            WorkflowNodeInput node,
            ReferenceKey key,
            String nodePath,
            boolean required,
            Map<String, Object> parameters,
            Integer timeout,
            CompileContext context
    ) {
        SubflowReferenceInput subflow = context.references().subflows().get(key);
        if (subflow == null) {
            throw new WorkflowCompileException("找不到已发布 Subflow 引用: " + display(key));
        }
        if (!key.equals(subflow.key())) {
            throw new WorkflowCompileException("Subflow 引用目录键与内容版本不一致: " + display(key));
        }
        validateOwnership(subflow.projectId(), subflow.targetId(), key, context);
        if (context.stack().contains(key)) {
            List<String> cycle = new ArrayList<>();
            context.stack().forEach(item -> cycle.add(display(item)));
            cycle.add(display(key));
            throw new RecursiveSubflowException("检测到递归 Subflow: " + String.join(" -> ", cycle));
        }

        context.stack().addLast(key);
        try {
            return expandGraph(
                    subflow.graph(),
                    nodePath + "/subflow:" + subflow.workflowId() + "@" + subflow.version(),
                    required,
                    parameters,
                    timeout,
                    context
            );
        } finally {
            context.stack().removeLast();
        }
    }

    private Expansion leafExpansion(
            WorkflowNodeInput sourceNode,
            CaseReferenceInput reference,
            String sourcePath,
            boolean required,
            Map<String, Object> inheritedParameters,
            Integer timeoutOverride
    ) {
        validateCaseReference(reference);
        int timeout = timeoutOverride == null ? reference.timeoutSeconds() : timeoutOverride;
        if (timeout < 1 || timeout > 86_400) {
            throw new WorkflowCompileException("叶节点 timeoutSeconds 必须在 1 到 86400 之间: " + sourcePath);
        }
        Map<String, Object> parameters = mergeParameters(reference.parameters(), inheritedParameters);
        UUID compiledId = UUID.nameUUIDFromBytes(sourcePath.getBytes(StandardCharsets.UTF_8));
        CompiledNode compiledNode = new CompiledNode(
                compiledId,
                sourcePath,
                sourceNode.type(),
                reference.kind(),
                reference.caseId(),
                reference.caseName(),
                reference.scriptVersionId(),
                reference.scriptVersion(),
                required,
                reference.runner().trim(),
                reference.sourceRef().trim(),
                reference.checksum(),
                timeout,
                parameters,
                reference.executionRequirement()
        );
        return new Expansion(
                Map.of(compiledId, compiledNode),
                Set.of(),
                Set.of(compiledId),
                Set.of(compiledId)
        );
    }

    private Map<UUID, WorkflowNodeInput> normalizedNodes(List<WorkflowNodeInput> nodes) {
        Map<UUID, WorkflowNodeInput> result = new TreeMap<>(UUID_COMPARATOR);
        for (WorkflowNodeInput node : nodes) {
            if (node == null || node.id() == null) {
                throw new WorkflowCompileException("Workflow 节点及节点 ID 不能为空");
            }
            if (result.putIfAbsent(node.id(), node) != null) {
                throw new WorkflowCompileException("Workflow 存在重复节点: " + node.id());
            }
        }
        return result;
    }

    private void validatePublishInput(WorkflowPublishInput input) {
        if (input == null) {
            throw new WorkflowCompileException("发布输入不能为空");
        }
        if (input.workflowId() == null || input.projectId() == null || input.targetId() == null) {
            throw new WorkflowCompileException("workflowId、projectId 和 targetId 不能为空");
        }
        if (input.version() < 1) {
            throw new WorkflowCompileException("Workflow 发布版本必须大于 0");
        }
        if (input.references() == null) {
            throw new WorkflowCompileException("引用目录不能为空");
        }
    }

    private void validateNode(WorkflowNodeInput node) {
        if (node.type() == null || node.referenceId() == null) {
            throw new WorkflowCompileException("Workflow 节点类型和引用 ID 不能为空: " + node.id());
        }
        if (node.referenceVersion() < 1) {
            throw new WorkflowCompileException("Workflow 节点必须引用固定版本: " + node.id());
        }
        if (node.timeoutSeconds() != null && (node.timeoutSeconds() < 1 || node.timeoutSeconds() > 86_400)) {
            throw new WorkflowCompileException("节点 timeoutSeconds 必须在 1 到 86400 之间: " + node.id());
        }
        validateParameterMap(node.parameterOverrides(), "节点参数覆盖");
    }

    private CaseReferenceInput requireCaseReference(ReferenceKey key, ReferenceCatalogInput references) {
        CaseReferenceInput reference = references.cases().get(key);
        if (reference == null) {
            throw new WorkflowCompileException("找不到固定脚本版本 Case 引用: " + display(key));
        }
        if (!key.equals(reference.key())) {
            throw new WorkflowCompileException("Case 引用目录键与内容版本不一致: " + display(key));
        }
        validateCaseReference(reference);
        return reference;
    }

    private void validateCaseReference(CaseReferenceInput reference) {
        if (reference == null || reference.caseId() == null || reference.scriptVersionId() == null
                || reference.projectId() == null || reference.targetId() == null || reference.kind() == null) {
            throw new WorkflowCompileException("Case 固定版本引用字段不完整");
        }
        if (reference.scriptVersion() < 1) {
            throw new WorkflowCompileException("Case 脚本版本必须大于 0: " + reference.caseId());
        }
        if (reference.runner() == null || reference.runner().isBlank()
                || reference.sourceRef() == null || reference.sourceRef().isBlank()) {
            throw new WorkflowCompileException("Case 固定版本缺少 runner 或 sourceRef: " + reference.caseId());
        }
        if (reference.checksum() == null || !reference.checksum().matches("^sha256:[a-f0-9]{64}$")) {
            throw new WorkflowCompileException("Case 脚本 checksum 非法: " + reference.caseId());
        }
        if (reference.timeoutSeconds() < 1 || reference.timeoutSeconds() > 86_400) {
            throw new WorkflowCompileException("Case timeoutSeconds 必须在 1 到 86400 之间: " + reference.caseId());
        }
        if (reference.executionRequirement() == null
                || reference.executionRequirement().executorType() == null
                || reference.executionRequirement().executorType().isBlank()
                || reference.executionRequirement().interaction() == null
                || reference.executionRequirement().resourceProfile() == null
                || reference.executionRequirement().resourceProfile().isBlank()) {
            throw new WorkflowCompileException("Case executionRequirement 不完整: " + reference.caseId());
        }
        if (!reference.runner().equalsIgnoreCase(reference.executionRequirement().executorType())) {
            throw new WorkflowCompileException("Case executorType 与固定脚本 runner 不一致: " + reference.caseId());
        }
        validateParameterMap(reference.parameters(), "Case 参数");
    }

    private void validateOwnership(
            UUID referenceProjectId,
            UUID referenceTargetId,
            ReferenceKey key,
            CompileContext context
    ) {
        if (!context.projectId().equals(referenceProjectId)) {
            throw new CrossProjectReferenceException(
                    "拒绝跨项目引用 " + display(key) + ": 期望 " + context.projectId() + "，实际 " + referenceProjectId
            );
        }
        if (!context.targetId().equals(referenceTargetId)) {
            throw new CrossProjectReferenceException(
                    "拒绝跨被测对象引用 " + display(key) + ": 期望 " + context.targetId() + "，实际 " + referenceTargetId
            );
        }
    }

    private Map<String, Object> mergeParameters(Map<String, Object> base, Map<String, Object> overrides) {
        validateParameterMap(base, "参数");
        validateParameterMap(overrides, "参数覆盖");
        Map<String, Object> result = new TreeMap<>();
        result.putAll(base);
        result.putAll(overrides);
        return Map.copyOf(result);
    }

    private void validateParameterMap(Map<String, Object> parameters, String label) {
        if (parameters == null) {
            throw new WorkflowCompileException(label + "不能为空");
        }
        parameters.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                throw new WorkflowCompileException(label + "的键和值不能为空");
            }
        });
    }

    private void merge(
            Expansion source,
            Map<UUID, CompiledNode> targetNodes,
            Set<CompiledEdge> targetEdges
    ) {
        source.nodes().forEach((nodeId, node) -> {
            CompiledNode previous = targetNodes.putIfAbsent(nodeId, node);
            if (previous != null && !previous.equals(node)) {
                throw new WorkflowCompileException("编译节点 ID 冲突: " + nodeId);
            }
        });
        targetEdges.addAll(source.edges());
    }

    private List<UUID> topologicalOrder(Map<UUID, CompiledNode> nodes, Collection<CompiledEdge> edges) {
        Map<UUID, Integer> indegree = new HashMap<>();
        Map<UUID, List<UUID>> successors = new HashMap<>();
        nodes.keySet().forEach(nodeId -> {
            indegree.put(nodeId, 0);
            successors.put(nodeId, new ArrayList<>());
        });
        for (CompiledEdge edge : edges) {
            if (!nodes.containsKey(edge.predecessorNodeId()) || !nodes.containsKey(edge.successorNodeId())) {
                throw new WorkflowCompileException("编译边引用了不存在的叶节点");
            }
            indegree.compute(edge.successorNodeId(), (ignored, count) -> count + 1);
            successors.get(edge.predecessorNodeId()).add(edge.successorNodeId());
        }
        successors.values().forEach(list -> list.sort(UUID_COMPARATOR));

        PriorityQueue<UUID> ready = new PriorityQueue<>(UUID_COMPARATOR);
        indegree.forEach((nodeId, degree) -> {
            if (degree == 0) {
                ready.add(nodeId);
            }
        });
        List<UUID> result = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            UUID nodeId = ready.remove();
            result.add(nodeId);
            for (UUID successor : successors.get(nodeId)) {
                int remaining = indegree.compute(successor, (ignored, degree) -> degree - 1);
                if (remaining == 0) {
                    ready.add(successor);
                }
            }
        }
        if (result.size() != nodes.size()) {
            throw new WorkflowCompileException("展开后的 Workflow 存在环，无法生成执行快照");
        }
        return List.copyOf(result);
    }

    private String display(ReferenceKey key) {
        return key.id() + "@" + key.version();
    }

    private record CompileContext(
            UUID projectId,
            UUID targetId,
            ReferenceCatalogInput references,
            Deque<ReferenceKey> stack
    ) {
    }

    private record Expansion(
            Map<UUID, CompiledNode> nodes,
            Set<CompiledEdge> edges,
            Set<UUID> entryNodeIds,
            Set<UUID> exitNodeIds
    ) {
    }
}
