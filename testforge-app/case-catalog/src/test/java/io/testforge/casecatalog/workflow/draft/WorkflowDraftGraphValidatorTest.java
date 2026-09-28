package io.testforge.casecatalog.workflow.draft;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.CYCLE;
import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.DANGLING_EDGE;
import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.DUPLICATE_EDGE;
import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.DUPLICATE_NODE_ID;
import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.EMPTY_GRAPH;
import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.INVALID_POSITION;
import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.INVALID_REFERENCE_VERSION;
import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.INVALID_TIMEOUT;
import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.MISSING_EDGE_CONDITION;
import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.MISSING_NODE_TYPE;
import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.MISSING_REFERENCE_ID;
import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.MISSING_REQUIRED;
import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.NULL_EDGE;
import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.NULL_NODE;
import static io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode.SELF_DEPENDENCY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowDraftGraphValidatorTest {

    private static final UUID WORKFLOW_ID = id(100);
    private static final UUID FIXTURE_ID = id(1);
    private static final UUID CASE_ID = id(2);
    private static final UUID SUITE_ID = id(3);
    private static final UUID SUBFLOW_ID = id(4);

    private final WorkflowDraftGraphValidator validator = new WorkflowDraftGraphValidator();

    @Test
    void shouldValidateFourNodeTypesAndMixedDependencyConditions() {
        var fixture = node(FIXTURE_ID, WorkflowNodeType.FIXTURE, true);
        var caseNode = new WorkflowDraftNode(
                CASE_ID,
                WorkflowNodeType.CASE,
                id(202),
                7,
                false,
                30,
                Map.of("region", "cn"),
                120.5,
                -8.25
        );
        var suite = node(SUITE_ID, WorkflowNodeType.SUITE, true);
        var subflow = node(SUBFLOW_ID, WorkflowNodeType.SUBFLOW, true);
        var draft = draft(
                List.of(fixture, caseNode, suite, subflow),
                List.of(
                        edge(FIXTURE_ID, CASE_ID, WorkflowEdgeCondition.ON_SUCCESS),
                        edge(FIXTURE_ID, SUITE_ID, WorkflowEdgeCondition.ON_COMPLETION),
                        edge(CASE_ID, SUBFLOW_ID, WorkflowEdgeCondition.ON_COMPLETION),
                        edge(SUITE_ID, SUBFLOW_ID, WorkflowEdgeCondition.ON_SUCCESS)
                )
        );

        ValidatedWorkflowDraft result = validator.validate(draft);

        assertEquals(List.of(FIXTURE_ID, CASE_ID, SUITE_ID, SUBFLOW_ID), result.topologicalNodeIds());
        assertEquals(List.of(
                WorkflowNodeType.FIXTURE,
                WorkflowNodeType.CASE,
                WorkflowNodeType.SUITE,
                WorkflowNodeType.SUBFLOW
        ), result.topologicalNodes().stream().map(WorkflowDraftNode::type).toList());
        assertEquals(false, caseNode.required());
        assertEquals(30, caseNode.timeoutSeconds());
        assertEquals(Map.of("region", "cn"), caseNode.parameterOverrides());
    }

    @Test
    void shouldDefensivelyCopyDraftCollectionsAndNodeParameters() {
        var nodes = new ArrayList<WorkflowDraftNode>();
        var edges = new ArrayList<WorkflowDraftEdge>();
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("browser", "edge");
        var node = new WorkflowDraftNode(
                CASE_ID,
                WorkflowNodeType.CASE,
                id(202),
                1,
                true,
                null,
                parameters,
                0,
                0
        );
        nodes.add(node);
        var draft = draft(nodes, edges);

        nodes.clear();
        edges.add(edge(CASE_ID, SUBFLOW_ID, WorkflowEdgeCondition.ON_SUCCESS));
        parameters.put("browser", "chrome");

        assertEquals(1, draft.nodes().size());
        assertTrue(draft.edges().isEmpty());
        assertEquals("edge", draft.nodes().getFirst().parameterOverrides().get("browser"));
        assertThrows(UnsupportedOperationException.class, () -> draft.nodes().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> draft.nodes().getFirst().parameterOverrides().put("new", "value"));
    }

    @Test
    void shouldRejectEmptyGraph() {
        var exception = assertInvalid(draft(List.of(), List.of()));

        assertTrue(exception.hasViolation(EMPTY_GRAPH));
    }

    @Test
    void shouldRejectDuplicateNodeIds() {
        var duplicate = new WorkflowDraftNode(
                CASE_ID,
                WorkflowNodeType.SUITE,
                id(333),
                2,
                true,
                null,
                Map.of(),
                10,
                20
        );

        var exception = assertInvalid(draft(
                List.of(node(CASE_ID, WorkflowNodeType.CASE, true), duplicate),
                List.of()
        ));

        assertTrue(exception.hasViolation(DUPLICATE_NODE_ID));
    }

    @Test
    void shouldRejectDuplicateEdgesByEndpointEvenWhenConditionsDiffer() {
        var exception = assertInvalid(draft(
                List.of(
                        node(CASE_ID, WorkflowNodeType.CASE, true),
                        node(SUITE_ID, WorkflowNodeType.SUITE, true)
                ),
                List.of(
                        edge(CASE_ID, SUITE_ID, WorkflowEdgeCondition.ON_SUCCESS),
                        edge(CASE_ID, SUITE_ID, WorkflowEdgeCondition.ON_COMPLETION)
                )
        ));

        assertTrue(exception.hasViolation(DUPLICATE_EDGE));
    }

    @Test
    void shouldRejectDanglingPredecessorAndSuccessor() {
        var exception = assertInvalid(draft(
                List.of(node(CASE_ID, WorkflowNodeType.CASE, true)),
                List.of(
                        edge(id(998), CASE_ID, WorkflowEdgeCondition.ON_SUCCESS),
                        edge(CASE_ID, id(999), WorkflowEdgeCondition.ON_COMPLETION)
                )
        ));

        assertEquals(2, exception.violations().stream()
                .filter(violation -> violation.code() == DANGLING_EDGE)
                .count());
    }

    @Test
    void shouldRejectSelfDependency() {
        var exception = assertInvalid(draft(
                List.of(node(CASE_ID, WorkflowNodeType.CASE, true)),
                List.of(edge(CASE_ID, CASE_ID, WorkflowEdgeCondition.ON_SUCCESS))
        ));

        assertTrue(exception.hasViolation(SELF_DEPENDENCY));
    }

    @Test
    void shouldReportExactCyclePath() {
        var exception = assertInvalid(draft(
                List.of(
                        node(FIXTURE_ID, WorkflowNodeType.FIXTURE, true),
                        node(CASE_ID, WorkflowNodeType.CASE, true),
                        node(SUITE_ID, WorkflowNodeType.SUITE, false),
                        node(SUBFLOW_ID, WorkflowNodeType.SUBFLOW, true)
                ),
                List.of(
                        edge(FIXTURE_ID, CASE_ID, WorkflowEdgeCondition.ON_SUCCESS),
                        edge(CASE_ID, SUITE_ID, WorkflowEdgeCondition.ON_SUCCESS),
                        edge(SUITE_ID, CASE_ID, WorkflowEdgeCondition.ON_COMPLETION),
                        edge(SUITE_ID, SUBFLOW_ID, WorkflowEdgeCondition.ON_COMPLETION)
                )
        ));

        assertTrue(exception.hasViolation(CYCLE));
        String message = exception.violations().getFirst().message();
        assertTrue(message.contains(CASE_ID.toString()));
        assertTrue(message.contains(SUITE_ID.toString()));
        assertTrue(message.endsWith(CASE_ID.toString()));
        assertTrue(!message.contains(SUBFLOW_ID.toString()), "环路提示不应把环下游节点误报为环成员");
    }

    @Test
    void shouldRejectMissingRequiredAndInvalidNodeFieldsTogether() {
        var invalidNode = new WorkflowDraftNode(
                CASE_ID,
                null,
                null,
                0,
                null,
                86_401,
                null,
                Double.NaN,
                Double.POSITIVE_INFINITY
        );

        var exception = assertInvalid(draft(List.of(invalidNode), List.of()));

        assertTrue(exception.hasViolation(MISSING_NODE_TYPE));
        assertTrue(exception.hasViolation(MISSING_REFERENCE_ID));
        assertTrue(exception.hasViolation(INVALID_REFERENCE_VERSION));
        assertTrue(exception.hasViolation(MISSING_REQUIRED));
        assertTrue(exception.hasViolation(INVALID_TIMEOUT));
        assertTrue(exception.hasViolation(INVALID_POSITION));
    }

    @Test
    void shouldRejectMissingEdgeCondition() {
        var exception = assertInvalid(draft(
                List.of(
                        node(CASE_ID, WorkflowNodeType.CASE, true),
                        node(SUITE_ID, WorkflowNodeType.SUITE, true)
                ),
                List.of(edge(CASE_ID, SUITE_ID, null))
        ));

        assertTrue(exception.hasViolation(MISSING_EDGE_CONDITION));
    }

    @Test
    void shouldRejectNullNodeAndEdgeWithoutLosingOtherViolations() {
        var nodes = new ArrayList<WorkflowDraftNode>();
        nodes.add(null);
        var edges = new ArrayList<WorkflowDraftEdge>();
        edges.add(null);

        var exception = assertInvalid(draft(nodes, edges));

        assertTrue(exception.hasViolation(NULL_NODE));
        assertTrue(exception.hasViolation(NULL_EDGE));
    }

    @Test
    void shouldUseDraftOrderAsStableTieBreakerForParallelNodes() {
        var third = node(SUITE_ID, WorkflowNodeType.SUITE, true);
        var first = node(FIXTURE_ID, WorkflowNodeType.FIXTURE, true);
        var merge = node(SUBFLOW_ID, WorkflowNodeType.SUBFLOW, true);
        var second = node(CASE_ID, WorkflowNodeType.CASE, true);
        var draft = draft(
                List.of(third, first, merge, second),
                List.of(
                        edge(FIXTURE_ID, SUBFLOW_ID, WorkflowEdgeCondition.ON_COMPLETION),
                        edge(CASE_ID, SUBFLOW_ID, WorkflowEdgeCondition.ON_SUCCESS)
                )
        );

        assertEquals(
                List.of(SUITE_ID, FIXTURE_ID, CASE_ID, SUBFLOW_ID),
                validator.validate(draft).topologicalNodeIds()
        );
        assertEquals(
                validator.validate(draft).topologicalNodes(),
                validator.topologicalSort(draft)
        );
    }

    private WorkflowDraftValidationException assertInvalid(WorkflowDraft draft) {
        return assertThrows(WorkflowDraftValidationException.class, () -> validator.validate(draft));
    }

    private WorkflowDraft draft(List<WorkflowDraftNode> nodes, List<WorkflowDraftEdge> edges) {
        return new WorkflowDraft(WORKFLOW_ID, nodes, edges);
    }

    private WorkflowDraftNode node(UUID nodeId, WorkflowNodeType type, boolean required) {
        return new WorkflowDraftNode(
                nodeId,
                type,
                id(1_000 + nodeId.hashCode()),
                1,
                required,
                null,
                Map.of(),
                0,
                0
        );
    }

    private WorkflowDraftEdge edge(
            UUID predecessorId,
            UUID successorId,
            WorkflowEdgeCondition condition
    ) {
        return new WorkflowDraftEdge(predecessorId, successorId, condition);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
