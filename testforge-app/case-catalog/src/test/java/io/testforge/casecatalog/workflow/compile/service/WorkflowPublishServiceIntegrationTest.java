package io.testforge.casecatalog.workflow.compile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.workflow.compile.entity.PublishedWorkflowVersionEntity;
import io.testforge.casecatalog.workflow.compile.model.CaseReferenceInput;
import io.testforge.casecatalog.workflow.compile.model.ExecutableNodeType;
import io.testforge.casecatalog.workflow.compile.model.PublishNodeType;
import io.testforge.casecatalog.workflow.compile.model.ReferenceCatalogInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowGraphInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowNodeInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowPublishInput;
import io.testforge.casecatalog.workflow.compile.repo.PublishedWorkflowVersionRepository;
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
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = WorkflowPublishServiceIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:workflow_publish;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=BINARY"
        }
)
@ActiveProfiles("test")
@Transactional
class WorkflowPublishServiceIntegrationTest {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-18T04:00:00Z");
    private static final String SCRIPT_CHECKSUM = "sha256:" + "b".repeat(64);

    @Autowired
    private PublishedWorkflowVersionRepository repository;

    @Autowired
    private ProjectCatalogService projectCatalogService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private WorkflowPublishService service;
    private UUID projectId;
    private UUID targetId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var project = projectCatalogService.createProject(new CreateProjectCommand(
                "Workflow 发布项目-" + suffix,
                "workflow-" + suffix
        ));
        var target = projectCatalogService.createTarget(project.id(), new CreateTargetCommand(
                "HTTP Target",
                TargetType.HTTP_SERVICE
        ));
        projectId = project.id();
        targetId = target.id();
        service = new WorkflowPublishService(
                repository,
                new WorkflowSnapshotCompiler(),
                new WorkflowSnapshotJsonCodec(objectMapper),
                Clock.fixed(PUBLISHED_AT, ZoneOffset.UTC),
                UUID::randomUUID
        );
    }

    @Test
    void shouldPersistCanonicalSnapshotAndMakeIdenticalPublishRetrySafe() {
        UUID workflowId = id("published-workflow");
        WorkflowPublishInput input = input(workflowId, null);

        var published = service.publish(input);
        var retried = service.publish(input);

        assertThat(retried.id()).isEqualTo(published.id());
        assertThat(retried.checksum()).isEqualTo(published.checksum());
        assertThat(retried.publishedAt()).isEqualTo(PUBLISHED_AT);
        assertThat(retried.compiledSnapshot()).isEqualTo(published.compiledSnapshot());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(published.checksum()).matches("sha256:[a-f0-9]{64}");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM case_catalog_workflow_version WHERE workflow_id = ?",
                String.class,
                workflowId
        )).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT compiled_snapshot FROM case_catalog_workflow_version WHERE workflow_id = ?",
                String.class,
                workflowId
        )).contains("\"schemaVersion\":1").contains("\"workflowVersion\":1");
    }

    @Test
    void shouldRejectChangingAnAlreadyPublishedVersionAndKeepOriginalSnapshot() {
        UUID workflowId = id("immutable-workflow");
        var original = service.publish(input(workflowId, null));

        assertThatThrownBy(() -> service.publish(input(workflowId, 60)))
                .isInstanceOf(PublishedVersionConflictException.class)
                .hasMessageContaining("已发布版本不可修改");

        var reloaded = service.get(workflowId, 1);
        assertThat(reloaded.id()).isEqualTo(original.id());
        assertThat(reloaded.checksum()).isEqualTo(original.checksum());
        assertThat(reloaded.compiledSnapshot().nodes().getFirst().timeoutSeconds()).isEqualTo(30);
        assertThat(repository.count()).isEqualTo(1);
    }

    private WorkflowPublishInput input(UUID workflowId, Integer timeoutOverride) {
        UUID caseId = id("publish-case");
        CaseReferenceInput reference = new CaseReferenceInput(
                caseId,
                1,
                projectId,
                targetId,
                ExecutableNodeType.CASE,
                id("publish-script"),
                "pytest-http",
                "cases/publish.py",
                SCRIPT_CHECKSUM,
                30,
                Map.of("locale", "zh-CN")
        );
        UUID nodeId = id("publish-node");
        return new WorkflowPublishInput(
                workflowId,
                projectId,
                targetId,
                1,
                new WorkflowGraphInput(
                        List.of(new WorkflowNodeInput(
                                nodeId,
                                PublishNodeType.CASE,
                                caseId,
                                1,
                                true,
                                timeoutOverride,
                                Map.of()
                        )),
                        List.of()
                ),
                new ReferenceCatalogInput(Map.of(reference.key(), reference), Map.of(), Map.of())
        );
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ProjectCatalogConfig.class, PublishedWorkflowPersistenceConfig.class})
    static class TestApplication {
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = PublishedWorkflowVersionEntity.class)
    @EnableJpaRepositories(basePackageClasses = PublishedWorkflowVersionRepository.class)
    static class PublishedWorkflowPersistenceConfig {
    }
}
