package io.testforge.projectcatalog.ctrl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.projectcatalog.model.ProjectState;
import io.testforge.projectcatalog.model.ProjectView;
import io.testforge.projectcatalog.model.TargetType;
import io.testforge.projectcatalog.model.TargetView;
import io.testforge.projectcatalog.service.ProjectCatalogNotFoundException;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import io.testforge.projectcatalog.revision.RevisionOption;
import io.testforge.projectcatalog.revision.RevisionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectCatalogControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProjectCatalogService service;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ProjectCatalogService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProjectCatalogController(service))
                .setControllerAdvice(new ProjectCatalogExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void shouldCreateTargetWithContractResponse() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(service.createTarget(eq(projectId), any())).thenReturn(
                new TargetView(targetId, projectId, "Windows Client", TargetType.DESKTOP, List.of())
        );

        mockMvc.perform(post("/api/v1/projects/{projectId}/targets", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Windows Client","type":"DESKTOP"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(targetId.toString()))
                .andExpect(jsonPath("$.data.type").value("DESKTOP"))
                .andExpect(jsonPath("$.data.environments").isArray())
                .andExpect(jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern("^[a-f0-9]{32}$")));
    }

    @Test
    void shouldCreateProjectWithoutUserFacingCode() throws Exception {
        UUID projectId = UUID.randomUUID();
        when(service.createProject(any())).thenReturn(
                new ProjectView(projectId, "Demo", projectId.toString(), ProjectState.ACTIVE, List.of())
        );

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Demo"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(projectId.toString()))
                .andExpect(jsonPath("$.data.code").value(projectId.toString()));
    }

    @Test
    void shouldReturnValidationErrorForUnsupportedTargetType() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{projectId}/targets", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Console","type":"GAME_CONSOLE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturnProjectAggregateAndMapMissingProject() throws Exception {
        UUID projectId = UUID.randomUUID();
        when(service.getProject(projectId)).thenReturn(
                new ProjectView(projectId, "Demo", "demo", ProjectState.ACTIVE, List.of())
        );

        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targets").isArray());

        UUID missingId = UUID.randomUUID();
        when(service.getProject(missingId)).thenThrow(new ProjectCatalogNotFoundException("项目不存在"));
        mockMvc.perform(get("/api/v1/projects/{projectId}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void shouldListProjectBranchesWithCommitMetadata() throws Exception {
        UUID projectId = UUID.randomUUID();
        when(service.listProjectRevisions(projectId, RevisionType.BRANCH)).thenReturn(List.of(
                new RevisionOption(RevisionType.BRANCH, "main", "a".repeat(40),
                        "latest change", Instant.parse("2026-09-24T00:00:00Z"), true)
        ));

        mockMvc.perform(get("/api/v1/projects/{projectId}/revisions", projectId)
                        .queryParam("type", "BRANCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("main"))
                .andExpect(jsonPath("$.data[0].commitMessage").value("latest change"))
                .andExpect(jsonPath("$.data[0].defaultBranch").value(true));
    }
}
