package io.testforge.runorchestrator.ctrl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.runorchestrator.run.model.CreateRunCommand;
import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.run.model.RunView;
import io.testforge.runorchestrator.run.service.RunIdempotencyConflictException;
import io.testforge.runorchestrator.run.service.RunNotFoundException;
import io.testforge.runorchestrator.run.service.RunStateConflictException;
import io.testforge.runorchestrator.run.service.RunTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RunControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private RunTaskService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(RunTaskService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RunController(service))
                .setControllerAdvice(new RunExceptionHandler())
                .build();
    }

    @Test
    void shouldCreateQueryAndCancelRunUsingTheVersionedPublicPath() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        RunView queued = view(runId, RunState.QUEUED);
        RunView cancelling = view(runId, RunState.CANCELLING);
        when(service.createRun(any())).thenReturn(queued);
        when(service.getRun(runId)).thenReturn(queued);
        when(service.requestCancellation(any(), any())).thenReturn(cancelling);

        String createBody = objectMapper.writeValueAsString(Map.of(
                "projectId", projectId,
                "targetId", targetId,
                "environmentId", environmentId,
                "workflowId", workflowId,
                "workflowVersion", 2,
                "priority", 7,
                "maxConcurrency", 3,
                "requestKey", requestKey
        ));
        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(runId.toString()))
                .andExpect(jsonPath("$.data.state").value("QUEUED"))
                .andExpect(jsonPath("$.data.totalTasks").value(0));

        ArgumentCaptor<CreateRunCommand> command = ArgumentCaptor.forClass(CreateRunCommand.class);
        org.mockito.Mockito.verify(service).createRun(command.capture());
        assertThat(command.getValue().requestKey()).isEqualTo(requestKey);
        assertThat(command.getValue().maxConcurrency()).isEqualTo(3);

        mockMvc.perform(get("/api/v1/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0));

        mockMvc.perform(post("/api/v1/runs/{runId}/cancel", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestKey", UUID.randomUUID(),
                                "reason", "用户停止演示"
                        ))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.state").value("CANCELLING"));
    }

    @Test
    void shouldMapValidationNotFoundIdempotencyAndStateConflicts() throws Exception {
        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        UUID missing = UUID.randomUUID();
        when(service.getRun(missing)).thenThrow(new RunNotFoundException("Run 不存在"));
        mockMvc.perform(get("/api/v1/runs/{runId}", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        when(service.createRun(any())).thenThrow(new RunIdempotencyConflictException("创建键冲突"));
        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        UUID terminal = UUID.randomUUID();
        when(service.requestCancellation(any(), any()))
                .thenThrow(new RunStateConflictException("终态不可取消"));
        mockMvc.perform(post("/api/v1/runs/{runId}/cancel", terminal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestKey", UUID.randomUUID(),
                                "reason", "停止"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    private String validCreateBody() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "projectId", UUID.randomUUID(),
                "targetId", UUID.randomUUID(),
                "environmentId", UUID.randomUUID(),
                "workflowId", UUID.randomUUID(),
                "workflowVersion", 1,
                "priority", 5,
                "maxConcurrency", 2,
                "requestKey", UUID.randomUUID()
        ));
    }

    private RunView view(UUID runId, RunState state) {
        Instant now = Instant.parse("2026-09-18T06:00:00Z");
        return new RunView(
                runId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "sha256:" + "a".repeat(64),
                state,
                5,
                2,
                UUID.randomUUID(),
                0,
                state == RunState.CANCELLING ? now : null,
                state == RunState.CANCELLING ? "用户停止演示" : null,
                now,
                now,
                null,
                Map.of(),
                java.util.List.of()
        );
    }
}
