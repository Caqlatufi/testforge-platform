package io.testforge.casecatalog.workflow.ctrl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.workflow.compile.model.CompiledWorkflowSnapshot;
import io.testforge.casecatalog.workflow.compile.model.PublishedWorkflowVersionView;
import io.testforge.casecatalog.workflow.draft.WorkflowDraft;
import io.testforge.casecatalog.workflow.draft.WorkflowDraftValidationException;
import io.testforge.casecatalog.workflow.draft.WorkflowDraftNode;
import io.testforge.casecatalog.workflow.draft.WorkflowDraftViolation;
import io.testforge.casecatalog.workflow.draft.WorkflowDraftViolationCode;
import io.testforge.casecatalog.workflow.draft.WorkflowNodeType;
import io.testforge.casecatalog.workflow.model.WorkflowView;
import io.testforge.casecatalog.workflow.service.TestWorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TestWorkflowControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TestWorkflowService service;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(TestWorkflowService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestWorkflowController(service))
                .setControllerAdvice(new WorkflowCatalogExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void shouldExposeCreateSaveAndPublishContracts() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        WorkflowView created = view(workflowId, projectId, targetId, List.of(), 0);
        WorkflowDraftNode node = new WorkflowDraftNode(
                nodeId, WorkflowNodeType.CASE, caseId, 1, true, null, Map.of(), 10, 20
        );
        WorkflowView saved = view(workflowId, projectId, targetId, List.of(node), 1);
        var snapshot = new CompiledWorkflowSnapshot(
                1, workflowId, 1, projectId, targetId, List.of(), List.of(), List.of()
        );
        var published = new PublishedWorkflowVersionView(
                UUID.randomUUID(), workflowId, projectId, targetId, 1,
                "sha256:" + "d".repeat(64), snapshot, Instant.parse("2026-09-18T00:00:00Z")
        );
        when(service.create(eq(projectId), any())).thenReturn(created);
        when(service.saveGraph(eq(workflowId), eq(0), any())).thenReturn(saved);
        when(service.publish(workflowId, requestKey)).thenReturn(published);

        mockMvc.perform(post("/api/v1/projects/{projectId}/workflows", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetId", targetId,
                                "name", "核心流程"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(workflowId.toString()));

        mockMvc.perform(put("/api/v1/workflows/{workflowId}/graph", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "nodes", List.of(Map.of(
                                        "id", nodeId,
                                        "type", "CASE",
                                        "referenceId", caseId,
                                        "referenceVersion", 1,
                                        "required", true,
                                        "parameterOverrides", Map.of(),
                                        "positionX", 10,
                                        "positionY", 20
                                )),
                                "edges", List.of()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draftRevision").value(1));

        mockMvc.perform(post("/api/v1/workflows/{workflowId}/publish", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("requestKey", requestKey))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.compiledSnapshot.schemaVersion").value(1));

        verify(service).publish(workflowId, requestKey);
    }

    @Test
    void shouldRejectEmptyGraphAtRestBoundary() throws Exception {
        mockMvc.perform(put("/api/v1/workflows/{workflowId}/graph", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "nodes", List.of(),
                                "edges", List.of()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldMapEmptyDraftPublishToStructuredBadRequest() throws Exception {
        UUID workflowId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        when(service.publish(workflowId, requestKey)).thenThrow(new WorkflowDraftValidationException(List.of(
                new WorkflowDraftViolation(
                        WorkflowDraftViolationCode.EMPTY_GRAPH,
                        "Workflow 草稿至少需要一个节点"
                )
        )));

        mockMvc.perform(post("/api/v1/workflows/{workflowId}/publish", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("requestKey", requestKey))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Workflow 草稿至少需要一个节点"))
                .andExpect(jsonPath("$.details.violations[0].code").value("EMPTY_GRAPH"));
    }

    private WorkflowView view(
            UUID workflowId,
            UUID projectId,
            UUID targetId,
            List<WorkflowDraftNode> nodes,
            int revision
    ) {
        Instant now = Instant.parse("2026-09-18T00:00:00Z");
        return new WorkflowView(
                workflowId,
                projectId,
                targetId,
                "核心流程",
                revision,
                0,
                new WorkflowDraft(workflowId, nodes, List.of()),
                now,
                now
        );
    }
}
