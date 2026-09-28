package io.testforge.casecatalog.suite.service;

import io.testforge.casecatalog.CaseCatalogConfig;
import io.testforge.casecatalog.suite.model.CreateTestSuiteCommand;
import io.testforge.casecatalog.suite.model.UpdateTestSuiteCommand;
import io.testforge.casecatalog.suite.repo.TestSuiteRepository;
import io.testforge.casecatalog.testcase.model.CreateTestCaseCommand;
import io.testforge.casecatalog.testcase.model.TestCaseKind;
import io.testforge.casecatalog.testcase.service.TestCaseService;
import io.testforge.projectcatalog.ProjectCatalogConfig;
import io.testforge.projectcatalog.model.CreateProjectCommand;
import io.testforge.projectcatalog.model.CreateTargetCommand;
import io.testforge.projectcatalog.model.TargetType;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = TestSuiteServiceIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:case_catalog_suite;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=BINARY"
        }
)
@ActiveProfiles("test")
@Transactional
class TestSuiteServiceIntegrationTest {

    @Autowired
    private TestSuiteService service;

    @Autowired
    private TestSuiteRepository repository;

    @Autowired
    private ProjectCatalogService projectCatalogService;

    @Autowired
    private TestCaseService testCaseService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID projectId;
    private UUID targetId;

    @BeforeEach
    void setUpAssets() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var project = projectCatalogService.createProject(new CreateProjectCommand(
                "套件项目-" + suffix,
                "suite-" + suffix
        ));
        var target = projectCatalogService.createTarget(project.id(), new CreateTargetCommand(
                "Windows Client",
                TargetType.DESKTOP
        ));
        projectId = project.id();
        targetId = target.id();
    }

    @Test
    void shouldPersistAnOrderIndependentDeduplicatedSuiteAndMakeIdenticalCreateRetrySafe() {
        UUID firstCase = createCase("登录检查");
        UUID secondCase = createCase("技能检查");

        var command = new CreateTestSuiteCommand(
                targetId,
                " 核心回归 ",
                List.of(secondCase, firstCase, secondCase),
                List.of("smoke", " smoke ", "regression"),
                Map.of("locale", "zh-CN", "retry", 0)
        );
        var created = service.create(projectId, command);
        entityManager.flush();
        entityManager.clear();

        var reloaded = service.get(created.id());
        var retried = service.create(projectId, new CreateTestSuiteCommand(
                targetId,
                "核心回归",
                List.of(firstCase, secondCase),
                List.of("regression", "smoke"),
                Map.of("retry", 0, "locale", "zh-CN")
        ));

        assertThat(reloaded.name()).isEqualTo("核心回归");
        assertThat(reloaded.caseIds()).containsExactlyInAnyOrder(firstCase, secondCase);
        assertThat(reloaded.tags()).containsExactly("regression", "smoke");
        assertThat(reloaded.parameterBindings()).containsEntry("locale", "zh-CN").containsEntry("retry", 0);
        assertThat(reloaded.version()).isEqualTo(1);
        assertThat(retried.id()).isEqualTo(created.id());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM case_catalog_test_suite_member",
                Integer.class
        )).isEqualTo(2);
    }

    @Test
    void shouldUpdateWithOptimisticVersionAndRejectStaleVersion() {
        UUID firstCase = createCase("冒烟用例");
        UUID secondCase = createCase("回归用例");
        var created = service.create(projectId, new CreateTestSuiteCommand(
                targetId,
                "冒烟",
                List.of(firstCase),
                List.of("smoke"),
                Map.of()
        ));

        var updated = service.update(created.id(), new UpdateTestSuiteCommand(
                created.version(),
                "冒烟与回归",
                List.of(secondCase, firstCase),
                List.of("nightly"),
                Map.of("region", "cn")
        ));

        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.caseIds()).containsExactlyInAnyOrder(firstCase, secondCase);
        assertThatThrownBy(() -> service.update(created.id(), new UpdateTestSuiteCommand(
                created.version(),
                "过期更新",
                List.of(firstCase),
                List.of(),
                Map.of()
        ))).isInstanceOf(SuiteCatalogConflictException.class)
                .hasMessageContaining("版本已变化");
    }

    @Test
    void shouldRejectMissingAndForeignCaseMembersBeforeWritingSuite() {
        UUID missingCaseId = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(projectId, new CreateTestSuiteCommand(
                targetId,
                "缺少成员",
                List.of(missingCaseId),
                List.of(),
                Map.of()
        ))).isInstanceOf(SuiteCatalogNotFoundException.class)
                .hasMessageContaining(missingCaseId.toString());

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var otherProject = projectCatalogService.createProject(new CreateProjectCommand(
                "其他项目-" + suffix,
                "other-" + suffix
        ));
        var otherTarget = projectCatalogService.createTarget(otherProject.id(), new CreateTargetCommand(
                "其他客户端",
                TargetType.DESKTOP
        ));
        UUID foreignCase = createCase(otherProject.id(), otherTarget.id(), "其他用例");

        assertThatThrownBy(() -> service.create(projectId, new CreateTestSuiteCommand(
                targetId,
                "错误归属",
                List.of(foreignCase),
                List.of(),
                Map.of()
        ))).isInstanceOf(SuiteCatalogValidationException.class)
                .hasMessageContaining("不属于指定项目与被测对象");
        assertThat(repository.count()).isZero();
    }

    private UUID createCase(String name) {
        return createCase(projectId, targetId, name);
    }

    private UUID createCase(UUID caseProjectId, UUID caseTargetId, String name) {
        return testCaseService.createTestCase(caseProjectId, new CreateTestCaseCommand(
                caseTargetId,
                name,
                TestCaseKind.ASSERTION,
                Map.of("type", "object", "additionalProperties", true),
                Set.of("P0"),
                30
        )).id();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ProjectCatalogConfig.class, CaseCatalogConfig.class})
    static class TestApplication {
    }
}
