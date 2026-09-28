package io.testforge.casecatalog.workflow.compile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.testcase.model.ExecutionRequirement;
import io.testforge.casecatalog.testcase.model.InteractionMode;
import io.testforge.casecatalog.testcase.model.LeaseScope;
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
import io.testforge.casecatalog.workflow.compile.model.WorkflowPublishInput;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowSnapshotCompilerTest {

    private static final UUID PROJECT_ID = id("project");
    private static final UUID TARGET_ID = id("target");
    private static final UUID WORKFLOW_ID = id("root-workflow");
    private static final String CHECKSUM = "sha256:" + "a".repeat(64);

    private final WorkflowSnapshotCompiler compiler = new WorkflowSnapshotCompiler();
    private final WorkflowSnapshotJsonCodec codec = new WorkflowSnapshotJsonCodec(new ObjectMapper());

    @Test
    void shouldExpandSuiteAndSubflowIntoStableTraceableLeafDag() {
        CaseReferenceInput setup = caseReference("setup", ExecutableNodeType.FIXTURE, PROJECT_ID, TARGET_ID);
        CaseReferenceInput suiteA = caseReference("suite-a", ExecutableNodeType.CASE, PROJECT_ID, TARGET_ID);
        CaseReferenceInput suiteB = caseReference("suite-b", ExecutableNodeType.CASE, PROJECT_ID, TARGET_ID);
        CaseReferenceInput subflowCase = caseReference("sub-case", ExecutableNodeType.CASE, PROJECT_ID, TARGET_ID);
        CaseReferenceInput cleanup = caseReference("cleanup", ExecutableNodeType.FIXTURE, PROJECT_ID, TARGET_ID);
        UUID suiteId = id("suite");
        UUID subflowId = id("subflow");
        UUID setupNode = id("setup-node");
        UUID suiteNode = id("suite-node");
        UUID subflowNode = id("subflow-node");
        UUID subCaseNode = id("sub-case-node");
        UUID cleanupNode = id("cleanup-node");

        var subflowGraph = new WorkflowGraphInput(
                List.of(
                        node(subCaseNode, PublishNodeType.CASE, subflowCase.key(), true),
                        node(cleanupNode, PublishNodeType.FIXTURE, cleanup.key(), true)
                ),
                List.of(new WorkflowEdgeInput(subCaseNode, cleanupNode, DependencyCondition.ON_COMPLETION))
        );
        var suite = new SuiteReferenceInput(
                suiteId,
                3,
                PROJECT_ID,
                TARGET_ID,
                List.of(suiteB, suiteA),
                Map.of("locale", "zh-CN")
        );
        var subflow = new SubflowReferenceInput(
                subflowId,
                2,
                PROJECT_ID,
                TARGET_ID,
                subflowGraph
        );

        var nodes = List.of(
                node(setupNode, PublishNodeType.FIXTURE, setup.key(), true),
                new WorkflowNodeInput(suiteNode, PublishNodeType.SUITE, suiteId, 3, true, 45, Map.of("retry", 1)),
                node(subflowNode, PublishNodeType.SUBFLOW, subflow.key(), false)
        );
        var edges = List.of(
                new WorkflowEdgeInput(setupNode, suiteNode, DependencyCondition.ON_SUCCESS),
                new WorkflowEdgeInput(suiteNode, subflowNode, DependencyCondition.ON_COMPLETION)
        );
        ReferenceCatalogInput references = references(
                List.of(setup, subflowCase, cleanup),
                List.of(suite),
                List.of(subflow)
        );

        var snapshot = compiler.compile(new WorkflowPublishInput(
                WORKFLOW_ID,
                PROJECT_ID,
                TARGET_ID,
                7,
                new WorkflowGraphInput(nodes, edges),
                references
        ));

        assertThat(snapshot.nodes()).hasSize(5);
        assertThat(snapshot.edges()).hasSize(5);
        assertThat(snapshot.edges()).filteredOn(edge -> edge.condition() == DependencyCondition.ON_SUCCESS)
                .hasSize(2);
        assertThat(snapshot.edges()).filteredOn(edge -> edge.condition() == DependencyCondition.ON_COMPLETION)
                .hasSize(3);
        assertThat(snapshot.topologicalOrder()).containsExactlyElementsOf(
                snapshot.nodes().stream().map(node -> node.id()).toList()
        );
        assertThat(snapshot.nodes()).filteredOn(node -> node.sourceType() == PublishNodeType.SUITE)
                .hasSize(2)
                .allSatisfy(node -> {
                    assertThat(node.timeoutSeconds()).isEqualTo(45);
                    assertThat(node.parameters()).containsEntry("locale", "zh-CN").containsEntry("retry", 1);
                    assertThat(node.sourcePath()).contains("suite:" + suiteId + "@3");
                });
        assertThat(snapshot.nodes()).filteredOn(node -> node.sourcePath().contains("subflow:" + subflowId + "@2"))
                .hasSize(2)
                .allSatisfy(node -> assertThat(node.required()).isFalse());

        // 草稿节点、边、Suite 成员和引用 Map 的遍历顺序变化，不得改变执行快照。
        var reversedSuite = new SuiteReferenceInput(
                suiteId, 3, PROJECT_ID, TARGET_ID, List.of(suiteA, suiteB), Map.of("locale", "zh-CN")
        );
        ReferenceCatalogInput reorderedReferences = references(
                List.of(cleanup, subflowCase, setup),
                List.of(reversedSuite),
                List.of(subflow)
        );
        var reordered = compiler.compile(new WorkflowPublishInput(
                WORKFLOW_ID,
                PROJECT_ID,
                TARGET_ID,
                7,
                new WorkflowGraphInput(List.of(nodes.get(2), nodes.get(1), nodes.get(0)), List.of(edges.get(1), edges.get(0))),
                reorderedReferences
        ));

        assertThat(reordered).isEqualTo(snapshot);
        assertThat(codec.write(reordered)).isEqualTo(codec.write(snapshot));
        assertThat(codec.checksum(codec.write(reordered))).isEqualTo(codec.checksum(codec.write(snapshot)));
    }

    @Test
    void shouldRejectDirectAndNestedCrossProjectReferences() {
        UUID foreignProject = id("foreign-project");
        CaseReferenceInput foreignCase = caseReference(
                "foreign-case", ExecutableNodeType.CASE, foreignProject, TARGET_ID
        );
        UUID directNode = id("direct-node");
        var directInput = new WorkflowPublishInput(
                WORKFLOW_ID,
                PROJECT_ID,
                TARGET_ID,
                1,
                new WorkflowGraphInput(List.of(node(directNode, PublishNodeType.CASE, foreignCase.key(), true)), List.of()),
                references(List.of(foreignCase), List.of(), List.of())
        );
        assertThatThrownBy(() -> compiler.compile(directInput))
                .isInstanceOf(CrossProjectReferenceException.class)
                .hasMessageContaining("拒绝跨项目引用");

        CaseReferenceInput foreignSuiteMember = caseReference(
                "foreign-suite-member", ExecutableNodeType.CASE, foreignProject, TARGET_ID
        );
        UUID suiteId = id("foreign-member-suite");
        var suite = new SuiteReferenceInput(
                suiteId, 1, PROJECT_ID, TARGET_ID, List.of(foreignSuiteMember), Map.of()
        );
        var suiteInput = new WorkflowPublishInput(
                WORKFLOW_ID,
                PROJECT_ID,
                TARGET_ID,
                1,
                new WorkflowGraphInput(
                        List.of(node(id("suite-node"), PublishNodeType.SUITE, suite.key(), true)),
                        List.of()
                ),
                references(List.of(), List.of(suite), List.of())
        );
        assertThatThrownBy(() -> compiler.compile(suiteInput))
                .isInstanceOf(CrossProjectReferenceException.class)
                .hasMessageContaining(foreignSuiteMember.caseId().toString());

        UUID foreignSubflowId = id("foreign-subflow");
        var foreignSubflow = new SubflowReferenceInput(
                foreignSubflowId,
                1,
                foreignProject,
                TARGET_ID,
                new WorkflowGraphInput(List.of(), List.of())
        );
        var subflowInput = new WorkflowPublishInput(
                WORKFLOW_ID,
                PROJECT_ID,
                TARGET_ID,
                1,
                new WorkflowGraphInput(
                        List.of(node(id("foreign-subflow-node"), PublishNodeType.SUBFLOW, foreignSubflow.key(), true)),
                        List.of()
                ),
                references(List.of(), List.of(), List.of(foreignSubflow))
        );
        assertThatThrownBy(() -> compiler.compile(subflowInput))
                .isInstanceOf(CrossProjectReferenceException.class)
                .hasMessageContaining(foreignSubflowId.toString());
    }

    @Test
    void shouldAllowSharedCaseFromAnotherProjectWithoutRelaxingProjectCases() {
        UUID sharedOriginProject = id("shared-origin-project");
        UUID sharedOriginTarget = id("shared-origin-target");
        CaseReferenceInput shared = new CaseReferenceInput(
                id("shared-health-case"), 1, sharedOriginProject, sharedOriginTarget,
                ExecutableNodeType.CASE, id("shared-health-script"), "pytest-http",
                "cases/shared-health.py", CHECKSUM, 30, Map.of(), true
        );
        var input = new WorkflowPublishInput(
                WORKFLOW_ID, PROJECT_ID, TARGET_ID, 1,
                new WorkflowGraphInput(
                        List.of(node(id("shared-node"), PublishNodeType.CASE, shared.key(), true)),
                        List.of()
                ),
                references(List.of(shared), List.of(), List.of())
        );

        var snapshot = compiler.compile(input);

        assertThat(snapshot.nodes()).singleElement()
                .satisfies(node -> assertThat(node.caseId()).isEqualTo(shared.caseId()));
    }

    @Test
    void shouldRejectRecursiveSubflowsWithCompleteCyclePath() {
        UUID workflowA = id("workflow-a");
        UUID workflowB = id("workflow-b");
        ReferenceKey keyA = new ReferenceKey(workflowA, 1);
        ReferenceKey keyB = new ReferenceKey(workflowB, 4);
        var graphA = new WorkflowGraphInput(
                List.of(node(id("a-to-b"), PublishNodeType.SUBFLOW, keyB, true)),
                List.of()
        );
        var graphB = new WorkflowGraphInput(
                List.of(node(id("b-to-a"), PublishNodeType.SUBFLOW, keyA, true)),
                List.of()
        );
        var references = references(
                List.of(),
                List.of(),
                List.of(
                        new SubflowReferenceInput(workflowA, 1, PROJECT_ID, TARGET_ID, graphA),
                        new SubflowReferenceInput(workflowB, 4, PROJECT_ID, TARGET_ID, graphB)
                )
        );

        assertThatThrownBy(() -> compiler.compile(new WorkflowPublishInput(
                workflowA, PROJECT_ID, TARGET_ID, 1, graphA, references
        )))
                .isInstanceOf(RecursiveSubflowException.class)
                .hasMessageContaining(workflowA + "@1")
                .hasMessageContaining(workflowB + "@4");
    }

    @Test
    void shouldRejectWrongFixedVersionAndExpandedCycle() {
        CaseReferenceInput caseReference = caseReference(
                "versioned", ExecutableNodeType.CASE, PROJECT_ID, TARGET_ID
        );
        var missingVersion = new ReferenceKey(caseReference.caseId(), 2);
        var missingInput = new WorkflowPublishInput(
                WORKFLOW_ID,
                PROJECT_ID,
                TARGET_ID,
                1,
                new WorkflowGraphInput(
                        List.of(node(id("wrong-version"), PublishNodeType.CASE, missingVersion, true)),
                        List.of()
                ),
                references(List.of(caseReference), List.of(), List.of())
        );
        assertThatThrownBy(() -> compiler.compile(missingInput))
                .isInstanceOf(WorkflowCompileException.class)
                .hasMessageContaining("找不到固定脚本版本");

        UUID first = id("cycle-first");
        UUID second = id("cycle-second");
        var cycleInput = new WorkflowPublishInput(
                WORKFLOW_ID,
                PROJECT_ID,
                TARGET_ID,
                1,
                new WorkflowGraphInput(
                        List.of(
                                node(first, PublishNodeType.CASE, caseReference.key(), true),
                                node(second, PublishNodeType.CASE, caseReference.key(), true)
                        ),
                        List.of(
                                new WorkflowEdgeInput(first, second, DependencyCondition.ON_SUCCESS),
                                new WorkflowEdgeInput(second, first, DependencyCondition.ON_COMPLETION)
                        )
                ),
                references(List.of(caseReference), List.of(), List.of())
        );
        assertThatThrownBy(() -> compiler.compile(cycleInput))
                .isInstanceOf(WorkflowCompileException.class)
                .hasMessageContaining("存在环");
    }

    @Test
    void shouldFreezeEachCaseResourceContractWithoutPropagatingItAlongDagEdges() {
        CaseReferenceInput headless = caseReference("headless", ExecutableNodeType.CASE, PROJECT_ID, TARGET_ID);
        CaseReferenceInput ui = new CaseReferenceInput(
                id("ui-case"), 1, PROJECT_ID, TARGET_ID, ExecutableNodeType.CASE,
                id("ui-script"), "airtest", "cases/ui.air", CHECKSUM, 60, Map.of(), false,
                new ExecutionRequirement("airtest", InteractionMode.UI, Set.of("windows"),
                        "ui-medium", LeaseScope.CASE, null)
        );
        UUID first = id("headless-node");
        UUID second = id("ui-node");

        var snapshot = compiler.compile(new WorkflowPublishInput(
                WORKFLOW_ID, PROJECT_ID, TARGET_ID, 1,
                new WorkflowGraphInput(
                        List.of(node(first, PublishNodeType.CASE, headless.key(), true),
                                node(second, PublishNodeType.CASE, ui.key(), true)),
                        List.of(new WorkflowEdgeInput(first, second, DependencyCondition.ON_SUCCESS))
                ),
                references(List.of(headless, ui), List.of(), List.of())
        ));

        assertThat(snapshot.nodes()).filteredOn(node -> node.caseId().equals(headless.caseId()))
                .singleElement().satisfies(node -> assertThat(node.executionRequirement().interaction())
                        .isEqualTo(InteractionMode.HEADLESS));
        assertThat(snapshot.nodes()).filteredOn(node -> node.caseId().equals(ui.caseId()))
                .singleElement().satisfies(node -> {
                    assertThat(node.executionRequirement().interaction()).isEqualTo(InteractionMode.UI);
                    assertThat(node.executionRequirement().resourceProfile()).isEqualTo("ui-medium");
                });
    }

    private static WorkflowNodeInput node(
            UUID nodeId,
            PublishNodeType type,
            ReferenceKey reference,
            boolean required
    ) {
        return new WorkflowNodeInput(nodeId, type, reference.id(), reference.version(), required, null, Map.of());
    }

    private static CaseReferenceInput caseReference(
            String seed,
            ExecutableNodeType kind,
            UUID projectId,
            UUID targetId
    ) {
        return new CaseReferenceInput(
                id(seed + "-case"),
                1,
                projectId,
                targetId,
                kind,
                id(seed + "-script"),
                "pytest-http",
                "cases/" + seed + ".py",
                CHECKSUM,
                30,
                Map.of("seed", seed)
        );
    }

    private static ReferenceCatalogInput references(
            List<CaseReferenceInput> cases,
            List<SuiteReferenceInput> suites,
            List<SubflowReferenceInput> subflows
    ) {
        Map<ReferenceKey, CaseReferenceInput> caseMap = new LinkedHashMap<>();
        cases.forEach(item -> caseMap.put(item.key(), item));
        Map<ReferenceKey, SuiteReferenceInput> suiteMap = new LinkedHashMap<>();
        suites.forEach(item -> suiteMap.put(item.key(), item));
        Map<ReferenceKey, SubflowReferenceInput> subflowMap = new LinkedHashMap<>();
        subflows.forEach(item -> subflowMap.put(item.key(), item));
        return new ReferenceCatalogInput(caseMap, suiteMap, subflowMap);
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
