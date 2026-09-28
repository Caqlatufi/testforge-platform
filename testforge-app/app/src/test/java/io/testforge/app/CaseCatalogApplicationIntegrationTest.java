package io.testforge.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.testforge.casecatalog.testcase.service.TestCaseService;
import io.testforge.casecatalog.workflow.draft.WorkflowDraft;
import io.testforge.casecatalog.workflow.draft.WorkflowDraftNode;
import io.testforge.casecatalog.workflow.draft.WorkflowNodeType;
import io.testforge.casecatalog.workflow.model.CreateWorkflowCommand;
import io.testforge.casecatalog.workflow.service.TestWorkflowService;
import io.testforge.projectcatalog.model.CreateProjectCommand;
import io.testforge.projectcatalog.model.CreateTargetCommand;
import io.testforge.projectcatalog.model.TargetType;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import io.testforge.runorchestrator.run.model.CreateRunCommand;
import io.testforge.runorchestrator.run.service.RunTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CaseCatalogApplicationIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ProjectCatalogService projects;

    @Autowired
    private TestCaseService cases;

    @Autowired
    private TestWorkflowService workflows;

    @Autowired
    private RunTaskService runs;

    @Test
    void exposesCaseScriptAndSuiteApisFromAggregatedApplication() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode project = postCreated("/api/v1/projects", Map.of(
                "name", "资产项目-" + suffix,
                "code", "assets-" + suffix
        ));
        UUID projectId = UUID.fromString(project.path("data").path("id").asText());

        JsonNode target = postCreated("/api/v1/projects/" + projectId + "/targets", Map.of(
                "name", "接口服务",
                "type", "HTTP_SERVICE"
        ));
        UUID targetId = UUID.fromString(target.path("data").path("id").asText());

        JsonNode testCase = postCreated("/api/v1/projects/" + projectId + "/cases", Map.of(
                "targetId", targetId,
                "name", "登录检查",
                "kind", "ASSERTION",
                "parameters", Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "required", List.of("userId"),
                        "properties", Map.of("userId", Map.of("type", "string", "minLength", 1))
                ),
                "tags", Set.of("P0", "smoke"),
                "timeoutSeconds", 30
        ));
        UUID caseId = UUID.fromString(testCase.path("data").path("id").asText());
        assertThat(testCase.path("data").path("tags").isArray()).isTrue();
        assertThat(testCase.path("data").path("tags").toString()).contains("P0", "smoke");
        assertThat(testCase.path("data").path("parameters").path("type").asText()).isEqualTo("object");

        JsonNode script = postCreated("/api/v1/cases/" + caseId + "/scripts", Map.of(
                "runner", "pytest-http",
                "sourceRef", "sample_cases/login.py",
                "checksum", "sha256:" + "a".repeat(64)
        ));
        assertThat(script.path("data").path("version").asInt()).isEqualTo(1);

        JsonNode suite = postCreated("/api/v1/projects/" + projectId + "/suites", Map.of(
                "targetId", targetId,
                "name", "核心回归",
                "caseIds", List.of(caseId, caseId),
                "tags", List.of("smoke", "regression"),
                "parameterBindings", Map.of("locale", "zh-CN")
        ));
        UUID suiteId = UUID.fromString(suite.path("data").path("id").asText());
        assertThat(suite.path("data").path("caseIds")).hasSize(1);
        assertThat(suite.path("data").path("version").asLong()).isEqualTo(1);

        JsonNode reloaded = restTemplate.getForObject("/api/v1/suites/" + suiteId, JsonNode.class);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.path("data").path("id").asText()).isEqualTo(suiteId.toString());
    }

    @Test
    void returnsTheSameErrorEnvelopeForCaseAndSuiteEndpoints() {
        ResponseEntity<JsonNode> invalidCase = restTemplate.postForEntity(
                "/api/v1/projects/" + UUID.randomUUID() + "/cases",
                Map.of("name", "缺少字段", "kind", "ASSERTION", "parameters", Map.of(), "timeoutSeconds", 0),
                JsonNode.class
        );
        ResponseEntity<JsonNode> invalidSuite = restTemplate.postForEntity(
                "/api/v1/projects/" + UUID.randomUUID() + "/suites",
                Map.of(
                        "targetId", UUID.randomUUID(),
                        "name", "空套件",
                        "caseIds", List.of(),
                        "tags", List.of(),
                        "parameterBindings", Map.of()
                ),
                JsonNode.class
        );

        assertValidationEnvelope(invalidCase);
        assertValidationEnvelope(invalidSuite);
    }

    @Test
    void createsProjectCaseAndSuiteSchemaForTestProfile() throws SQLException {
        assertTableReadable("project_catalog_project");
        assertTableReadable("case_catalog_test_case");
        assertTableReadable("case_catalog_test_suite");
    }

    @Test
    void freezesCurrentCaseDefinitionWhenEachRunIsCreated() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var project = projects.createProject(new CreateProjectCommand("执行快照-" + suffix, "snapshot-" + suffix));
        var target = projects.createTarget(project.id(), new CreateTargetCommand("HTTP Target", TargetType.HTTP_SERVICE));
        var testCase = cases.createDefinition(project.id(), yamlCase("snapshot-case", "assert True"));
        var workflow = workflows.create(project.id(), new CreateWorkflowCommand(target.id(), "snapshot-workflow"));
        UUID nodeId = UUID.randomUUID();
        workflows.saveGraph(workflow.id(), 0, new WorkflowDraft(
                workflow.id(),
                List.of(new WorkflowDraftNode(
                        nodeId, WorkflowNodeType.CASE, testCase.id(), 1,
                        true, null, Map.of(), 100, 100
                )),
                List.of()
        ));
        var published = workflows.publish(workflow.id(), UUID.randomUUID());

        var first = runs.createRun(new CreateRunCommand(
                project.id(), target.id(), null, workflow.id(), published.version(), 5, 10, UUID.randomUUID()
        ));
        cases.updateDefinition(testCase.id(), yamlCase("snapshot-case", "assert False"));
        var second = runs.createRun(new CreateRunCommand(
                project.id(), target.id(), null, workflow.id(), published.version(), 5, 10, UUID.randomUUID()
        ));

        assertThat(workflows.get(workflow.id()).latestVersion()).isEqualTo(1);
        assertThat(first.workflowVersion()).isEqualTo(second.workflowVersion()).isEqualTo(1);
        assertThat(first.workflowChecksum()).isNotEqualTo(second.workflowChecksum());
        assertThat(first.tasks()).singleElement().satisfies(task ->
                assertThat(task.scriptChecksum()).isNotEqualTo(second.tasks().getFirst().scriptChecksum()));
        assertThat(runs.getExecutionSnapshot(first.id()).nodes().getFirst().scriptChecksum())
                .isEqualTo(first.tasks().getFirst().scriptChecksum());
    }

    private void assertTableReadable(String tableName) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + tableName);
             var resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
        }
    }

    private JsonNode postCreated(String path, Object body) {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(path, body, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("OK");
        assertThat(response.getBody().path("traceId").asText()).matches("^[a-f0-9]{32}$");
        return response.getBody();
    }

    private String yamlCase(String name, String assertion) {
        return """
                apiVersion: testforge.io/v1alpha1
                kind: TestCase
                metadata:
                  name: %s
                  tags: [snapshot]
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

    private void assertValidationEnvelope(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().path("message").asText()).isNotBlank();
        assertThat(response.getBody().path("details").isObject()).isTrue();
        assertThat(response.getBody().path("traceId").asText()).matches("^[a-f0-9]{32}$");
    }
}
