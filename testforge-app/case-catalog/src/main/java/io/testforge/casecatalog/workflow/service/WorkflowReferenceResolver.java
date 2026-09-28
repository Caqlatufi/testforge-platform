package io.testforge.casecatalog.workflow.service;

import io.testforge.casecatalog.suite.service.SuiteCatalogException;
import io.testforge.casecatalog.suite.service.TestSuiteService;
import io.testforge.casecatalog.testcase.model.CaseExecutionView;
import io.testforge.casecatalog.testcase.model.TestCaseKind;
import io.testforge.casecatalog.testcase.model.CaseScope;
import io.testforge.casecatalog.testcase.service.TestCaseCatalogException;
import io.testforge.casecatalog.testcase.service.TestCaseService;
import io.testforge.casecatalog.workflow.compile.model.CaseReferenceInput;
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
import io.testforge.casecatalog.workflow.compile.service.PublishedVersionNotFoundException;
import io.testforge.casecatalog.workflow.compile.service.WorkflowPublishService;
import io.testforge.casecatalog.workflow.draft.WorkflowDraft;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 通过 Case、Suite 与已发布 Workflow 的公开服务，将草稿固定引用解析为编译器目录。
 */
public final class WorkflowReferenceResolver {

    private final TestCaseService testCaseService;
    private final TestSuiteService testSuiteService;
    private final WorkflowPublishService publishService;

    public WorkflowReferenceResolver(
            TestCaseService testCaseService,
            TestSuiteService testSuiteService,
            WorkflowPublishService publishService
    ) {
        this.testCaseService = testCaseService;
        this.testSuiteService = testSuiteService;
        this.publishService = publishService;
    }

    public ReferenceCatalogInput resolve(UUID workflowId, WorkflowDraft draft) {
        Map<ReferenceKey, CaseReferenceInput> cases = new LinkedHashMap<>();
        Map<ReferenceKey, SuiteReferenceInput> suites = new LinkedHashMap<>();
        Map<ReferenceKey, SubflowReferenceInput> subflows = new LinkedHashMap<>();

        resolveDraft(workflowId, draft, cases, suites, subflows, new LinkedHashSet<>());
        return new ReferenceCatalogInput(cases, suites, subflows);
    }

    private void resolveDraft(
            UUID workflowId,
            WorkflowDraft draft,
            Map<ReferenceKey, CaseReferenceInput> cases,
            Map<ReferenceKey, SuiteReferenceInput> suites,
            Map<ReferenceKey, SubflowReferenceInput> subflows,
            Set<UUID> visitingWorkflowIds
    ) {
        if (!visitingWorkflowIds.add(workflowId)) {
            throw new WorkflowValidationException("检测到递归 Subflow: " + workflowId);
        }

        try {
            for (var node : draft.nodes()) {
                try {
                    switch (node.type()) {
                    case CASE, FIXTURE -> putCase(
                            cases,
                            toCaseReference(testCaseService.requireExecutionVersion(
                                    node.referenceId(),
                                    node.referenceVersion()
                            ))
                    );
                    case SUITE -> {
                        var suite = testSuiteService.get(node.referenceId());
                        if (suite.version() != node.referenceVersion()) {
                            throw new WorkflowValidationException(
                                    "Suite 固定版本不存在或已变化: " + node.referenceId()
                                            + "@" + node.referenceVersion()
                            );
                        }
                        List<CaseReferenceInput> members = suite.caseIds().stream()
                                .map(testCaseService::requireLatestExecutionVersion)
                                .map(this::toCaseReference)
                                .toList();
                        members.forEach(member -> putCase(cases, member));
                        var reference = new SuiteReferenceInput(
                                suite.id(),
                                Math.toIntExact(suite.version()),
                                suite.projectId(),
                                suite.targetId(),
                                members,
                                suite.parameterBindings()
                        );
                        suites.put(reference.key(), reference);
                    }
                    case SUBFLOW -> {
                        if (visitingWorkflowIds.contains(node.referenceId())) {
                            throw new WorkflowValidationException(
                                    "Workflow 不能递归引用自身或祖先 Subflow: " + node.referenceId()
                            );
                        }
                        var published = publishService.get(node.referenceId(), node.referenceVersion());
                        WorkflowGraphInput graph;
                        if (published.displaySnapshot() == null) {
                            graph = graphFromPublishedSnapshot(published.compiledSnapshot(), cases);
                        } else {
                            WorkflowDraft subflowDraft = published.displaySnapshot().graph();
                            resolveDraft(
                                    published.workflowId(),
                                    subflowDraft,
                                    cases,
                                    suites,
                                    subflows,
                                    visitingWorkflowIds
                            );
                            graph = toPublishGraph(subflowDraft);
                        }
                        var reference = new SubflowReferenceInput(
                                published.workflowId(),
                                published.version(),
                                published.projectId(),
                                published.targetId(),
                                graph
                        );
                        subflows.put(reference.key(), reference);
                    }
                    }
                } catch (TestCaseCatalogException | SuiteCatalogException | PublishedVersionNotFoundException exception) {
                    throw new WorkflowValidationException(exception.getMessage());
                }
            }
        } finally {
            visitingWorkflowIds.remove(workflowId);
        }
    }

    public WorkflowGraphInput toPublishGraph(WorkflowDraft draft) {
        return new WorkflowGraphInput(
                draft.nodes().stream()
                        .map(node -> new WorkflowNodeInput(
                                node.id(),
                                PublishNodeType.valueOf(node.type().name()),
                                node.referenceId(),
                                node.referenceVersion(),
                                node.required(),
                                node.timeoutSeconds(),
                                node.parameterOverrides()
                        ))
                        .toList(),
                draft.edges().stream()
                        .map(edge -> new WorkflowEdgeInput(
                                edge.predecessorNodeId(),
                                edge.successorNodeId(),
                                DependencyCondition.valueOf(edge.condition().name())
                        ))
                        .toList()
        );
    }

    private WorkflowGraphInput graphFromPublishedSnapshot(
            io.testforge.casecatalog.workflow.compile.model.CompiledWorkflowSnapshot snapshot,
            Map<ReferenceKey, CaseReferenceInput> cases
    ) {
        List<WorkflowNodeInput> nodes = snapshot.nodes().stream()
                .map(node -> {
                    putCase(cases, new CaseReferenceInput(
                            node.caseId(),
                            node.displayName(),
                            node.scriptVersion(),
                            snapshot.projectId(),
                            snapshot.targetId(),
                            node.type(),
                            node.scriptVersionId(),
                            node.runner(),
                            node.sourceRef(),
                            node.scriptChecksum(),
                            node.timeoutSeconds(),
                            Map.of(),
                            false,
                            node.executionRequirement()
                    ));
                    return new WorkflowNodeInput(
                            node.id(),
                            node.type() == ExecutableNodeType.CASE ? PublishNodeType.CASE : PublishNodeType.FIXTURE,
                            node.caseId(),
                            node.scriptVersion(),
                            node.required(),
                            node.timeoutSeconds(),
                            node.parameters()
                    );
                })
                .toList();
        List<WorkflowEdgeInput> edges = snapshot.edges().stream()
                .map(edge -> new WorkflowEdgeInput(
                        edge.predecessorNodeId(),
                        edge.successorNodeId(),
                        edge.condition()
                ))
                .toList();
        return new WorkflowGraphInput(nodes, edges);
    }

    private CaseReferenceInput toCaseReference(CaseExecutionView view) {
        return new CaseReferenceInput(
                view.caseId(),
                view.name(),
                view.scriptVersion(),
                view.projectId(),
                view.targetId(),
                view.kind() == TestCaseKind.ASSERTION ? ExecutableNodeType.CASE : ExecutableNodeType.FIXTURE,
                view.scriptVersionId(),
                view.runner().contractValue(),
                view.sourceRef(),
                view.checksum(),
                view.timeoutSeconds(),
                Map.of(),
                view.scope() == CaseScope.SHARED,
                view.executionRequirement()
        );
    }

    private void putCase(Map<ReferenceKey, CaseReferenceInput> cases, CaseReferenceInput candidate) {
        CaseReferenceInput existing = cases.putIfAbsent(candidate.key(), candidate);
        if (existing != null && !existing.equals(candidate)) {
            throw new WorkflowValidationException("同一 Case 固定版本解析结果不一致: " + candidate.key());
        }
    }
}
