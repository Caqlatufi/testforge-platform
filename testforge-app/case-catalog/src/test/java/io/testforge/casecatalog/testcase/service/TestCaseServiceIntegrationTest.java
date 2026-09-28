package io.testforge.casecatalog.testcase.service;

import io.testforge.casecatalog.testcase.TestCaseDomainConfig;
import io.testforge.casecatalog.testcase.asset.service.CaseAssetService;
import io.testforge.casecatalog.testcase.model.CreateScriptVersionCommand;
import io.testforge.casecatalog.testcase.model.CreateTestCaseCommand;
import io.testforge.casecatalog.testcase.model.ExecutionRequirement;
import io.testforge.casecatalog.testcase.model.InteractionMode;
import io.testforge.casecatalog.testcase.model.LeaseScope;
import io.testforge.casecatalog.testcase.model.ScriptRunner;
import io.testforge.casecatalog.testcase.model.TestCaseKind;
import io.testforge.casecatalog.testcase.model.CaseScope;
import io.testforge.casecatalog.testcase.repo.TestCaseRepository;
import io.testforge.casecatalog.testcase.repo.TestScriptVersionRepository;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = TestCaseServiceIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testcase_catalog;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=BINARY"
        }
)
@ActiveProfiles("test")
@Transactional
class TestCaseServiceIntegrationTest {

    @Autowired
    private ProjectCatalogService projectCatalogService;

    @Autowired
    private TestCaseService testCaseService;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private TestScriptVersionRepository scriptVersionRepository;

    @Autowired
    private CaseAssetService caseAssetService;

    private UUID projectId;
    private UUID targetId;

    @BeforeEach
    void setUpAssets() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var project = projectCatalogService.createProject(new CreateProjectCommand(
                "用例项目-" + suffix,
                "case-" + suffix
        ));
        var target = projectCatalogService.createTarget(project.id(), new CreateTargetCommand(
                "接口服务",
                TargetType.HTTP_SERVICE
        ));
        projectId = project.id();
        targetId = target.id();
    }

    @Test
    void shouldPersistAtomicCaseTagsAndValidateParameters() {
        var created = testCaseService.createTestCase(projectId, caseCommand(targetId));

        assertThat(created.projectId()).isEqualTo(projectId);
        assertThat(created.targetId()).isEqualTo(targetId);
        assertThat(created.tags()).containsExactly("P0", "smoke");
        assertThat(created.parameters()).containsEntry("type", "object");
        assertThat(created.scriptVersionId()).isNull();
        assertThat(testCaseRepository.count()).isEqualTo(1);

        Map<String, Object> valid = testCaseService.validateParameters(created.id(), Map.of(
                "userId", "u-100",
                "retries", 2
        ));
        assertThat(valid).containsEntry("userId", "u-100");

        assertThatThrownBy(() -> testCaseService.validateParameters(created.id(), Map.of("retries", 2)))
                .isInstanceOf(TestCaseValidationException.class)
                .hasMessageContaining("userId");
        assertThatThrownBy(() -> testCaseService.validateParameters(created.id(), Map.of(
                "userId", "u-100",
                "retries", 9
        ))).isInstanceOf(TestCaseValidationException.class)
                .hasMessageContaining("maximum");
    }

    @Test
    void shouldAppendImmutableScriptVersionsAndMoveCasePointer() {
        var testCase = testCaseService.createTestCase(projectId, caseCommand(targetId));
        String checksum1 = "sha256:" + "a".repeat(64);
        String checksum2 = "sha256:" + "b".repeat(64);

        var version1 = testCaseService.createScriptVersion(testCase.id(), new CreateScriptVersionCommand(
                ScriptRunner.PYTEST_HTTP,
                "sample_cases/login_v1.py",
                checksum1
        ));
        var version2 = testCaseService.createScriptVersion(testCase.id(), new CreateScriptVersionCommand(
                ScriptRunner.AIRTEST,
                "sample_cases/login_v2.air",
                checksum2
        ));

        assertThat(version1.version()).isEqualTo(1);
        assertThat(version2.version()).isEqualTo(2);
        assertThat(testCaseService.getTestCase(testCase.id()).scriptVersionId()).isEqualTo(version2.id());
        assertThat(testCaseService.listScriptVersions(testCase.id()))
                .extracting(version -> version.checksum())
                .containsExactly(checksum1, checksum2);
        assertThat(scriptVersionRepository.findById(version1.id()).orElseThrow().getSourceRef())
                .isEqualTo("sample_cases/login_v1.py");
    }

    @Test
    void shouldRejectForeignTargetInvalidSchemaAndInvalidChecksum() {
        var otherProject = projectCatalogService.createProject(new CreateProjectCommand("其他项目", "other-project"));
        var otherTarget = projectCatalogService.createTarget(otherProject.id(), new CreateTargetCommand(
                "其他接口",
                TargetType.HTTP_SERVICE
        ));

        assertThatThrownBy(() -> testCaseService.createTestCase(projectId, caseCommand(otherTarget.id())))
                .isInstanceOf(TestCaseValidationException.class)
                .hasMessageContaining("不属于");

        var invalidSchema = new CreateTestCaseCommand(
                targetId,
                "错误 Schema",
                TestCaseKind.ASSERTION,
                Map.of("type", "string"),
                Set.of(),
                30
        );
        assertThatThrownBy(() -> testCaseService.createTestCase(projectId, invalidSchema))
                .isInstanceOf(TestCaseValidationException.class)
                .hasMessageContaining("object");

        var testCase = testCaseService.createTestCase(projectId, caseCommand(targetId));
        assertThatThrownBy(() -> testCaseService.createScriptVersion(testCase.id(), new CreateScriptVersionCommand(
                ScriptRunner.PYTEST_HTTP,
                "sample_cases/login.py",
                "sha256:not-valid"
        ))).isInstanceOf(TestCaseValidationException.class)
                .hasMessageContaining("checksum");
        assertThat(scriptVersionRepository.count()).isZero();
    }

    @Test
    void shouldCreateYamlFirstCaseAndExposeMaterializedExecutionSnapshot() {
        String yaml = """
                apiVersion: testforge.io/v1alpha1
                kind: TestCase
                metadata:
                  name: YAML health
                  tags: [smoke]
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
                    entrypoint: test_health.py
                    content: |
                      def test_health():
                          assert True
                """;

        var validated = testCaseService.validateDefinition(projectId, yaml);
        assertThat(validated.valid()).isTrue();
        assertThat(validated.sourceRef()).isEqualTo("inline://pending");

        var created = testCaseService.createDefinition(projectId, yaml);
        assertThat(created.yamlManaged()).isTrue();
        assertThat(created.scriptVersionId()).isNull();
        var storedDefinition = testCaseService.getDefinition(created.id());
        assertThat(storedDefinition.yaml()).isEqualTo(yaml);
        assertThat(storedDefinition.assetId()).isNotNull();
        assertThat(storedDefinition.entrypoint()).isEqualTo("test_health.py");

        var execution = testCaseService.requireExecutionVersion(created.id(), 1);
        assertThat(execution.sourceRef()).startsWith("testforge://assets/").endsWith("#test_health.py");
        assertThat(execution.checksum()).matches("sha256:[a-f0-9]{64}");
        assertThatThrownBy(() -> testCaseService.createScriptVersion(created.id(), new CreateScriptVersionCommand(
                ScriptRunner.PYTEST_HTTP, "legacy.py", "sha256:" + "a".repeat(64)
        ))).isInstanceOf(TestCaseConflictException.class).hasMessageContaining("YAML Case");
        assertThat(scriptVersionRepository.count()).isZero();
    }

    @Test
    void shouldInferAirtestEntrypointAndRejectZipFileAsEntrypoint() throws IOException {
        var asset = caseAssetService.store(
                projectId, "login.air.zip", "application/zip",
                zip(Map.of(
                        "login.air/login.py", "print('login')",
                        "scripts/support.py", "VALUE = 1"
                ))
        );
        assertThat(asset.entrypoints()).containsExactly("login.air");
        assertThat(asset.yamlSnippet()).contains("entrypoint: \"login.air\"");

        String yaml = """
                apiVersion: testforge.io/v1alpha1
                kind: TestCase
                metadata:
                  name: Airtest login
                  tags: [airtest]
                spec:
                  type: assertion
                  timeoutSeconds: 30
                  execution:
                    executor: airtest
                    interaction: UI
                    capabilities: [WINDOWS_UI]
                    resourceProfile: ui-default
                    leaseScope: CASE
                  parameters:
                    type: object
                    additionalProperties: true
                  script:
                    type: asset
                    asset: %s
                """.formatted(asset.uri());
        assertThat(testCaseService.validateDefinition(projectId, yaml).entrypoint()).isEqualTo("login.air");

        assertThatThrownBy(() -> testCaseService.validateDefinition(
                projectId, yaml + "    entrypoint: login.air.zip\n"
        )).isInstanceOf(TestCaseValidationException.class)
                .hasMessageContaining("不存在于 Asset");
    }

    @Test
    void shouldRejectAssetPackagesWithMultiplePrimaryEntrypoints() throws IOException {
        assertThatThrownBy(() -> caseAssetService.store(
                projectId, "ambiguous.air.zip", "application/zip",
                zip(Map.of(
                        "first.air/first.py", "print('first')",
                        "second.air/second.py", "print('second')",
                        "scripts/support.py", "VALUE = 1"
                ))
        )).isInstanceOf(TestCaseValidationException.class)
                .hasMessageContaining("只允许一个主入口")
                .hasMessageContaining("first.air")
                .hasMessageContaining("second.air");
    }

    @Test
    void shouldResolveTheProjectPrimaryTargetWhenTheClientOmitsTargetId() {
        var created = testCaseService.createTestCase(projectId, new CreateTestCaseCommand(
                null,
                "项目健康检查",
                TestCaseKind.ASSERTION,
                Map.of("type", "object", "additionalProperties", true),
                Set.of("smoke"),
                30
        ));

        assertThat(created.projectId()).isEqualTo(projectId);
        assertThat(created.targetId()).isEqualTo(targetId);
    }

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            for (var file : files.entrySet()) {
                archive.putNextEntry(new ZipEntry(file.getKey()));
                archive.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
            }
        }
        return output.toByteArray();
    }

    @Test
    void shouldUpdateCaseFieldsWithoutChangingOwnership() {
        var created = testCaseService.createTestCase(projectId, caseCommand(targetId));
        var updated = testCaseService.updateTestCase(created.id(), new CreateTestCaseCommand(
                null,
                "登录检查 v2",
                TestCaseKind.FIXTURE,
                Map.of("type", "object", "additionalProperties", true),
                Set.of("regression"),
                45
        ));

        assertThat(updated.name()).isEqualTo("登录检查 v2");
        assertThat(updated.kind()).isEqualTo(TestCaseKind.FIXTURE);
        assertThat(updated.tags()).containsExactly("regression");
        assertThat(updated.timeoutSeconds()).isEqualTo(45);
        assertThatThrownBy(() -> testCaseService.updateTestCase(created.id(), new CreateTestCaseCommand(
                UUID.randomUUID(), "非法迁移", TestCaseKind.ASSERTION,
                Map.of("type", "object"), Set.of(), 30
        ))).isInstanceOf(TestCaseValidationException.class).hasMessageContaining("不能改变");
    }

    @Test
    void shouldExposeSharedCasesInThePlatformLibrary() {
        var shared = testCaseService.createTestCase(projectId, new CreateTestCaseCommand(
                targetId, "通用健康检查", TestCaseKind.ASSERTION, CaseScope.SHARED,
                Map.of("type", "object", "additionalProperties", true), Set.of("common"), 30
        ));
        var projectOnly = testCaseService.createTestCase(projectId, new CreateTestCaseCommand(
                targetId, "项目登录检查", TestCaseKind.ASSERTION, CaseScope.PROJECT,
                Map.of("type", "object", "additionalProperties", true), Set.of(), 30
        ));

        assertThat(testCaseService.listSharedTestCases())
                .extracting(item -> item.id())
                .contains(shared.id())
                .doesNotContain(projectOnly.id());
        assertThat(shared.scope()).isEqualTo(CaseScope.SHARED);
    }

    @Test
    void shouldPersistCaseResourceContractAndExposeItToWorkflowPublishing() {
        ExecutionRequirement requirement = new ExecutionRequirement(
                "airtest", InteractionMode.UI, Set.of("WINDOWS", "DESKTOP"),
                "ui-medium", LeaseScope.CASE, null
        );
        var created = testCaseService.createTestCase(projectId, new CreateTestCaseCommand(
                targetId, "Windows UI 回归", TestCaseKind.ASSERTION, CaseScope.PROJECT,
                Map.of("type", "object", "additionalProperties", true), Set.of("ui"), 120,
                requirement
        ));
        var script = testCaseService.createScriptVersion(created.id(), new CreateScriptVersionCommand(
                ScriptRunner.AIRTEST, "cases/windows-ui.air", "sha256:" + "c".repeat(64)
        ));

        assertThat(testCaseService.getTestCase(created.id()).executionRequirement()).isEqualTo(requirement);
        assertThat(testCaseService.requireExecutionVersion(created.id(), script.version()).executionRequirement())
                .isEqualTo(requirement);
    }

    private CreateTestCaseCommand caseCommand(UUID commandTargetId) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("userId"),
                "properties", Map.of(
                        "userId", Map.of("type", "string", "minLength", 1),
                        "retries", Map.of("type", "integer", "minimum", 0, "maximum", 3)
                )
        );
        return new CreateTestCaseCommand(
                commandTargetId,
                "登录检查",
                TestCaseKind.ASSERTION,
                schema,
                Set.of(" smoke ", "P0"),
                30
        );
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ProjectCatalogConfig.class, TestCaseDomainConfig.class})
    static class TestApplication {
    }
}
