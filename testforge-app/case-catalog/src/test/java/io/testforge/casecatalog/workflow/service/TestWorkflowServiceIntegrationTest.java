package io.testforge.casecatalog.workflow.service;

import io.testforge.casecatalog.CaseCatalogConfig;
import io.testforge.casecatalog.suite.model.CreateTestSuiteCommand;
import io.testforge.casecatalog.suite.model.UpdateTestSuiteCommand;
import io.testforge.casecatalog.suite.service.TestSuiteService;
import io.testforge.casecatalog.testcase.model.CreateScriptVersionCommand;
import io.testforge.casecatalog.testcase.model.CreateTestCaseCommand;
import io.testforge.casecatalog.testcase.model.ScriptRunner;
import io.testforge.casecatalog.testcase.model.TestCaseKind;
import io.testforge.casecatalog.testcase.model.TestCaseView;
import io.testforge.casecatalog.testcase.service.TestCaseService;
import io.testforge.casecatalog.workflow.compile.repo.PublishedWorkflowVersionRepository;
import io.testforge.casecatalog.workflow.draft.WorkflowDraft;
import io.testforge.casecatalog.workflow.draft.WorkflowDraftEdge;
import io.testforge.casecatalog.workflow.draft.WorkflowDraftNode;
import io.testforge.casecatalog.workflow.draft.WorkflowDraftValidationException;
import io.testforge.casecatalog.workflow.draft.WorkflowEdgeCondition;
import io.testforge.casecatalog.workflow.draft.WorkflowNodeType;
import io.testforge.casecatalog.workflow.model.CreateWorkflowCommand;
import io.testforge.projectcatalog.ProjectCatalogConfig;
import io.testforge.projectcatalog.model.CreateProjectCommand;
import io.testforge.projectcatalog.model.CreateTargetCommand;
import io.testforge.projectcatalog.model.TargetType;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = TestWorkflowServiceIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:workflow_service;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=BINARY"
        }
)
@ActiveProfiles("test")
@Transactional
class TestWorkflowServiceIntegrationTest {

    private static final String CHECKSUM = "sha256:" + "c".repeat(64);

    @Autowired
    private ProjectCatalogService projectCatalogService;

    @Autowired
    private TestCaseService testCaseService;

    @Autowired
    private TestSuiteService testSuiteService;

    @Autowired
    private TestWorkflowService workflowService;

    @Autowired
    private PublishedWorkflowVersionRepository publishedRepository;

    private UUID projectId;
    private UUID targetId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var project = projectCatalogService.createProject(new CreateProjectCommand(
                "Workflow 项目-" + suffix,
                "workflow-service-" + suffix
        ));
        var target = projectCatalogService.createTarget(project.id(), new CreateTargetCommand(
                "HTTP Target",
                TargetType.HTTP_SERVICE
        ));
        projectId = project.id();
        targetId = target.id();
    }

    @Test
    void shouldPersistDraftExpandSuiteAndKeepPublishedSnapshotImmutable() {
        TestCaseView first = createCase("case-a", TestCaseKind.ASSERTION);
        TestCaseView second = createCase("case-b", TestCaseKind.ASSERTION);
        var suite = testSuiteService.create(projectId, new CreateTestSuiteCommand(
                targetId,
                "smoke-suite",
                List.of(second.id(), first.id()),
                List.of("smoke"),
                Map.of("locale", "zh-CN")
        ));
        var workflow = workflowService.create(
                projectId,
                new CreateWorkflowCommand(targetId, "suite-workflow")
        );
        UUID suiteNodeId = UUID.randomUUID();
        var saved = workflowService.saveGraph(
                workflow.id(),
                0,
                new WorkflowDraft(
                        workflow.id(),
                        List.of(node(suiteNodeId, WorkflowNodeType.SUITE, suite.id(), 1)),
                        List.of()
                )
        );

        UUID requestKey = UUID.randomUUID();
        var published = workflowService.publish(workflow.id(), requestKey);
        var retried = workflowService.publish(workflow.id(), requestKey);

        assertThat(saved.draftRevision()).isEqualTo(1);
        assertThat(published.id()).isEqualTo(retried.id());
        assertThat(published.version()).isEqualTo(1);
        assertThat(published.compiledSnapshot().nodes()).hasSize(2);
        assertThat(published.compiledSnapshot().nodes())
                .allMatch(node -> node.sourcePath().contains("/suite:" + suite.id() + "@1/"));
        assertThat(published.displaySnapshot()).isNotNull();
        assertThat(published.displaySnapshot().graph().nodes()).singleElement()
                .satisfies(node -> assertThat(node.id()).isEqualTo(suiteNodeId));
        assertThat(published.displaySnapshot().nodeMapping().get(suiteNodeId)).hasSize(2);
        assertThat(publishedRepository.count()).isEqualTo(1);

        String originalChecksum = published.checksum();
        testSuiteService.update(suite.id(), new UpdateTestSuiteCommand(
                1,
                suite.name(),
                List.of(first.id()),
                suite.tags(),
                suite.parameterBindings()
        ));

        assertThat(workflowService.getPublished(workflow.id(), 1).checksum()).isEqualTo(originalChecksum);
        assertThatThrownBy(() -> workflowService.publish(workflow.id(), UUID.randomUUID()))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Suite 固定版本不存在或已变化");
    }

    @Test
    void shouldRejectCycleCrossProjectReferenceAndRecursiveSubflow() {
        TestCaseView localCase = createCase("local", TestCaseKind.ASSERTION);
        var workflow = workflowService.create(
                projectId,
                new CreateWorkflowCommand(targetId, "validated-workflow")
        );
        UUID firstNode = UUID.randomUUID();
        UUID secondNode = UUID.randomUUID();
        WorkflowDraft cyclic = new WorkflowDraft(
                workflow.id(),
                List.of(
                        node(firstNode, WorkflowNodeType.CASE, localCase.id(), 1),
                        node(secondNode, WorkflowNodeType.CASE, localCase.id(), 1)
                ),
                List.of(
                        new WorkflowDraftEdge(firstNode, secondNode, WorkflowEdgeCondition.ON_SUCCESS),
                        new WorkflowDraftEdge(secondNode, firstNode, WorkflowEdgeCondition.ON_COMPLETION)
                )
        );
        assertThatThrownBy(() -> workflowService.saveGraph(workflow.id(), 0, cyclic))
                .isInstanceOf(WorkflowDraftValidationException.class)
                .hasMessageContaining("DAG");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var foreignProject = projectCatalogService.createProject(new CreateProjectCommand(
                "Foreign-" + suffix,
                "foreign-" + suffix
        ));
        var foreignTarget = projectCatalogService.createTarget(foreignProject.id(), new CreateTargetCommand(
                "Foreign Target",
                TargetType.HTTP_SERVICE
        ));
        TestCaseView foreignCase = createCase(
                foreignProject.id(),
                foreignTarget.id(),
                "foreign-case",
                TestCaseKind.ASSERTION
        );
        workflowService.saveGraph(
                workflow.id(),
                0,
                new WorkflowDraft(
                        workflow.id(),
                        List.of(node(UUID.randomUUID(), WorkflowNodeType.CASE, foreignCase.id(), 1)),
                        List.of()
                )
        );
        assertThatThrownBy(() -> workflowService.publish(workflow.id(), UUID.randomUUID()))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("跨项目引用");

        workflowService.saveGraph(
                workflow.id(),
                1,
                new WorkflowDraft(
                        workflow.id(),
                        List.of(node(UUID.randomUUID(), WorkflowNodeType.SUBFLOW, workflow.id(), 1)),
                        List.of()
                )
        );
        assertThatThrownBy(() -> workflowService.publish(workflow.id(), UUID.randomUUID()))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("递归引用自身");
    }

    @Test
    void shouldExpandPublishedSubflowIntoParentStableSnapshot() {
        TestCaseView testCase = createCase("subflow-case", TestCaseKind.ASSERTION);
        var child = workflowService.create(projectId, new CreateWorkflowCommand(targetId, "child"));
        workflowService.saveGraph(
                child.id(),
                0,
                new WorkflowDraft(
                        child.id(),
                        List.of(node(UUID.randomUUID(), WorkflowNodeType.CASE, testCase.id(), 1)),
                        List.of()
                )
        );
        workflowService.publish(child.id(), UUID.randomUUID());

        var parent = workflowService.create(projectId, new CreateWorkflowCommand(targetId, "parent"));
        workflowService.saveGraph(
                parent.id(),
                0,
                new WorkflowDraft(
                        parent.id(),
                        List.of(node(UUID.randomUUID(), WorkflowNodeType.SUBFLOW, child.id(), 1)),
                        List.of()
                )
        );
        var published = workflowService.publish(parent.id(), UUID.randomUUID());

        assertThat(published.compiledSnapshot().nodes()).singleElement()
                .satisfies(node -> {
                    assertThat(node.caseId()).isEqualTo(testCase.id());
                    assertThat(node.sourcePath()).contains("/subflow:" + child.id() + "@1/");
                });
    }

    @Test
    void shouldResolveCurrentYamlCaseIntoANewExecutionSnapshotWithoutRepublishingTopology() {
        TestCaseView testCase = testCaseService.createDefinition(projectId, yamlCase("live-case", "assert True"));
        var workflow = workflowService.create(projectId, new CreateWorkflowCommand(targetId, "live-workflow"));
        workflowService.saveGraph(
                workflow.id(),
                0,
                new WorkflowDraft(
                        workflow.id(),
                        List.of(node(UUID.randomUUID(), WorkflowNodeType.CASE, testCase.id(), 1)),
                        List.of()
                )
        );
        var published = workflowService.publish(workflow.id(), UUID.randomUUID());
        var firstExecution = workflowService.resolveForExecution(workflow.id(), published.version());

        testCaseService.updateDefinition(testCase.id(), yamlCase("live-case", "assert False"));
        var secondExecution = workflowService.resolveForExecution(workflow.id(), published.version());

        assertThat(workflowService.get(workflow.id()).latestVersion()).isEqualTo(1);
        assertThat(workflowService.getPublished(workflow.id(), 1).checksum()).isEqualTo(published.checksum());
        assertThat(firstExecution.version()).isEqualTo(secondExecution.version()).isEqualTo(1);
        assertThat(firstExecution.compiledSnapshot().nodes()).singleElement()
                .satisfies(first -> assertThat(secondExecution.compiledSnapshot().nodes().getFirst().scriptChecksum())
                        .isNotEqualTo(first.scriptChecksum()));
        assertThat(firstExecution.checksum()).isNotEqualTo(secondExecution.checksum());
    }

    private TestCaseView createCase(String name, TestCaseKind kind) {
        return createCase(projectId, targetId, name, kind);
    }

    private TestCaseView createCase(UUID ownerProjectId, UUID ownerTargetId, String name, TestCaseKind kind) {
        TestCaseView testCase = testCaseService.createTestCase(ownerProjectId, new CreateTestCaseCommand(
                ownerTargetId,
                name,
                kind,
                Map.of("type", "object", "additionalProperties", true),
                Set.of("workflow"),
                30
        ));
        testCaseService.createScriptVersion(testCase.id(), new CreateScriptVersionCommand(
                ScriptRunner.PYTEST_HTTP,
                "cases/" + name + ".py",
                CHECKSUM
        ));
        return testCase;
    }

    private WorkflowDraftNode node(UUID nodeId, WorkflowNodeType type, UUID referenceId, int version) {
        return new WorkflowDraftNode(
                nodeId,
                type,
                referenceId,
                version,
                true,
                null,
                Map.of(),
                10,
                20
        );
    }

    private String yamlCase(String name, String assertion) {
        return """
                apiVersion: testforge.io/v1alpha1
                kind: TestCase
                metadata:
                  name: %s
                  tags: [workflow]
                spec:
                  type: assertion
                  timeoutSeconds: 30
                  execution:
                    executor: pytest-http
                    interaction: HEADLESS
                    capabilities: []
                    resourceProfile: script-small
                    leaseScope: CASE
                  parameters:
                    type: object
                    additionalProperties: true
                  script:
                    type: inline
                    language: python
                    entrypoint: test_case.py
                    content: |
                      def test_case():
                          %s
                """.formatted(name, assertion);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ProjectCatalogConfig.class, CaseCatalogConfig.class})
    static class TestApplication {
    }
}
