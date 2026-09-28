package io.testforge.casecatalog.testcase.ctrl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.testcase.model.ScriptRunner;
import io.testforge.casecatalog.testcase.model.ScriptVersionView;
import io.testforge.casecatalog.testcase.model.TestCaseKind;
import io.testforge.casecatalog.testcase.model.TestCaseView;
import io.testforge.casecatalog.testcase.service.TestCaseNotFoundException;
import io.testforge.casecatalog.testcase.service.TestCaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TestCaseControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TestCaseService service;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(TestCaseService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestCaseController(service))
                .setControllerAdvice(new TestCaseExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void shouldCreateCaseWithSchemaAndTags() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        when(service.createTestCase(eq(projectId), any())).thenReturn(new TestCaseView(
                caseId,
                projectId,
                targetId,
                "登录检查",
                TestCaseKind.ASSERTION,
                Set.of("P0", "smoke"),
                Map.of("type", "object"),
                30,
                null,
                Instant.parse("2026-09-18T00:00:00Z"),
                Instant.parse("2026-09-18T00:00:00Z")
        ));

        mockMvc.perform(post("/api/v1/projects/{projectId}/cases", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetId", targetId,
                                "name", "登录检查",
                                "kind", "ASSERTION",
                                "parameters", Map.of("type", "object"),
                                "tags", Set.of("P0", "smoke"),
                                "timeoutSeconds", 30
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(caseId.toString()))
                .andExpect(jsonPath("$.data.parameters.type").value("object"))
                .andExpect(jsonPath("$.data.tags").isArray())
                .andExpect(jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern("^[a-f0-9]{32}$")));
    }

    @Test
    void shouldAcceptProjectCaseWithoutLegacyTargetId() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        when(service.createTestCase(eq(projectId), any())).thenReturn(new TestCaseView(
                caseId, projectId, targetId, "健康检查", TestCaseKind.ASSERTION,
                Set.of(), Map.of("type", "object"), 30, null,
                Instant.parse("2026-09-18T00:00:00Z"),
                Instant.parse("2026-09-18T00:00:00Z")
        ));

        mockMvc.perform(post("/api/v1/projects/{projectId}/cases", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "健康检查",
                                "kind", "ASSERTION",
                                "parameters", Map.of("type", "object"),
                                "tags", Set.of(),
                                "timeoutSeconds", 30
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(caseId.toString()));
    }

    @Test
    void shouldAppendScriptVersionUsingContractRunnerValue() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String checksum = "sha256:" + "a".repeat(64);
        when(service.createScriptVersion(eq(caseId), any())).thenReturn(new ScriptVersionView(
                versionId,
                caseId,
                ScriptRunner.PYTEST_HTTP,
                "sample_cases/login.py",
                checksum,
                1,
                Instant.parse("2026-09-18T00:00:00Z")
        ));

        mockMvc.perform(post("/api/v1/cases/{caseId}/scripts", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "runner", "pytest-http",
                                "sourceRef", "sample_cases/login.py",
                                "checksum", checksum
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(versionId.toString()))
                .andExpect(jsonPath("$.data.runner").value("pytest-http"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void shouldRejectMalformedSchemaRequestAndMapMissingCase() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{projectId}/cases", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"缺少字段","kind":"ASSERTION","parameters":{},"timeoutSeconds":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        UUID caseId = UUID.randomUUID();
        when(service.createScriptVersion(eq(caseId), any()))
                .thenThrow(new TestCaseNotFoundException("用例不存在"));
        mockMvc.perform(post("/api/v1/cases/{caseId}/scripts", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "runner", "airtest",
                                "sourceRef", "case.air",
                                "checksum", "sha256:" + "b".repeat(64)
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
