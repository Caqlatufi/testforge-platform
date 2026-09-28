package io.testforge.dispatcher.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.CaseCatalogConfig;
import io.testforge.casecatalog.workflow.compile.model.CaseReferenceInput;
import io.testforge.casecatalog.workflow.compile.model.ExecutableNodeType;
import io.testforge.casecatalog.workflow.compile.model.PublishNodeType;
import io.testforge.casecatalog.workflow.compile.model.ReferenceCatalogInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowGraphInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowNodeInput;
import io.testforge.casecatalog.workflow.compile.model.WorkflowPublishInput;
import io.testforge.casecatalog.workflow.compile.service.WorkflowPublishService;
import io.testforge.dispatcher.DispatcherConfig;
import io.testforge.dispatcher.outbox.model.OutboxStatus;
import io.testforge.dispatcher.outbox.repo.OutboxEventRepository;
import io.testforge.projectcatalog.ProjectCatalogConfig;
import io.testforge.projectcatalog.model.CreateEnvironmentCommand;
import io.testforge.projectcatalog.model.CreateProjectCommand;
import io.testforge.projectcatalog.model.CreateTargetCommand;
import io.testforge.projectcatalog.model.TargetType;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import io.testforge.runorchestrator.RunOrchestratorConfig;
import io.testforge.runorchestrator.run.model.CreateRunCommand;
import io.testforge.runorchestrator.run.service.RunTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = TaskCreationOutboxIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:task_creation_outbox;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=BINARY"
        }
)
class TaskCreationOutboxIntegrationTest {

    private static final String CHECKSUM = "sha256:" + "c".repeat(64);

    @Autowired
    private ProjectCatalogService projectCatalogService;

    @Autowired
    private WorkflowPublishService workflowPublishService;

    @Autowired
    private RunTaskService runTaskService;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsOnlyRootTaskOutboxEventsInsideRunCreationTransaction() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var project = projectCatalogService.createProject(new CreateProjectCommand(
                "Outbox 项目-" + suffix,
                "outbox-" + suffix
        ));
        var target = projectCatalogService.createTarget(project.id(), new CreateTargetCommand(
                "HTTP Target",
                TargetType.HTTP_SERVICE
        ));
        var environment = projectCatalogService.createEnvironment(target.id(), new CreateEnvironmentCommand(
                "Test-" + suffix,
                "https://outbox-" + suffix + ".example.test",
                Map.of(),
                Map.of()
        ));

        UUID workflowId = id("workflow-" + suffix);
        UUID caseId = id("case-" + suffix);
        UUID nodeId = id("node-" + suffix);
        CaseReferenceInput reference = new CaseReferenceInput(
                caseId,
                1,
                project.id(),
                target.id(),
                ExecutableNodeType.CASE,
                id("script-" + suffix),
                "pytest-http",
                "cases/smoke.py",
                CHECKSUM,
                30,
                Map.of("platform", "linux", "requiredFeatures", List.of("network"))
        );
        workflowPublishService.publish(new WorkflowPublishInput(
                workflowId,
                project.id(),
                target.id(),
                1,
                new WorkflowGraphInput(
                        List.of(new WorkflowNodeInput(
                                nodeId,
                                PublishNodeType.CASE,
                                caseId,
                                1,
                                true,
                                null,
                                Map.of()
                        )),
                        List.of()
                ),
                new ReferenceCatalogInput(
                        Map.of(reference.key(), reference),
                        Map.of(),
                        Map.of()
                )
        ));

        CreateRunCommand command = new CreateRunCommand(
                project.id(),
                target.id(),
                environment.id(),
                workflowId,
                1,
                7,
                2,
                UUID.randomUUID()
        );
        var created = runTaskService.createRun(command);
        var duplicate = runTaskService.createRun(command);

        assertThat(duplicate.id()).isEqualTo(created.id());
        assertThat(outboxRepository.count()).isEqualTo(1);
        var event = outboxRepository.findAll().getFirst().toView();
        assertThat(event.eventKey()).isEqualTo(
                "task-ready:" + created.tasks().getFirst().id() + ":attempt:1"
        );
        assertThat(event.status()).isEqualTo(OutboxStatus.PENDING);
        var payload = objectMapper.readTree(event.payload());
        assertThat(payload.path("taskId").asText()).isEqualTo(created.tasks().getFirst().id().toString());
        assertThat(payload.path("runId").asText()).isEqualTo(created.id().toString());
        assertThat(payload.path("runner").asText()).isEqualTo("pytest-http");
        assertThat(payload.path("resourceMode").asText()).isEqualTo("PROCESS_POOL");
        assertThat(payload.path("platform").asText()).isEqualTo("linux");
        assertThat(payload.path("priority").asInt()).isEqualTo(7);
        assertThat(payload.path("attemptNo").asInt()).isEqualTo(1);
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            ProjectCatalogConfig.class,
            CaseCatalogConfig.class,
            RunOrchestratorConfig.class,
            DispatcherConfig.class
    })
    static class TestApplication {
    }
}
