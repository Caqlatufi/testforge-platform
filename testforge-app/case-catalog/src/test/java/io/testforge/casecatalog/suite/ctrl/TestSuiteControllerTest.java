package io.testforge.casecatalog.suite.ctrl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.suite.model.TestSuiteView;
import io.testforge.casecatalog.suite.service.SuiteCatalogNotFoundException;
import io.testforge.casecatalog.suite.service.TestSuiteService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TestSuiteControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TestSuiteService service;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(TestSuiteService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestSuiteController(service))
                .setControllerAdvice(new SuiteCatalogExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void shouldCreateAndUpdateSuiteWithContractResponse() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        when(service.create(eq(projectId), any())).thenReturn(view(suiteId, projectId, targetId, caseId, 1));
        when(service.update(eq(suiteId), any())).thenReturn(view(suiteId, projectId, targetId, caseId, 2));

        String createBody = objectMapper.writeValueAsString(Map.of(
                "targetId", targetId,
                "name", "核心回归",
                "caseIds", List.of(caseId, caseId),
                "tags", List.of("smoke"),
                "parameterBindings", Map.of("locale", "zh-CN")
        ));
        mockMvc.perform(post("/api/v1/projects/{projectId}/suites", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(suiteId.toString()))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern("^[a-f0-9]{32}$")));

        String updateBody = objectMapper.writeValueAsString(Map.of(
                "expectedVersion", 1,
                "name", "核心回归",
                "caseIds", List.of(caseId),
                "tags", List.of("smoke"),
                "parameterBindings", Map.of()
        ));
        mockMvc.perform(put("/api/v1/suites/{suiteId}", suiteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void shouldRejectEmptyMemberSetAndMapMissingSuite() throws Exception {
        UUID projectId = UUID.randomUUID();
        String invalidBody = objectMapper.writeValueAsString(Map.of(
                "targetId", UUID.randomUUID(),
                "name", "空套件",
                "caseIds", List.of(),
                "tags", List.of(),
                "parameterBindings", Map.of()
        ));
        mockMvc.perform(post("/api/v1/projects/{projectId}/suites", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        UUID missingId = UUID.randomUUID();
        when(service.get(missingId)).thenThrow(new SuiteCatalogNotFoundException("TestSuite 不存在"));
        mockMvc.perform(get("/api/v1/suites/{suiteId}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private TestSuiteView view(UUID suiteId, UUID projectId, UUID targetId, UUID caseId, long version) {
        Instant now = Instant.parse("2026-09-18T00:00:00Z");
        return new TestSuiteView(
                suiteId,
                projectId,
                targetId,
                "核心回归",
                List.of(caseId),
                List.of("smoke"),
                Map.of("locale", "zh-CN"),
                version,
                now,
                now
        );
    }
}
