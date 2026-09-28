package io.testforge.workergateway.callback.ctrl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.testforge.workergateway.callback.model.AttemptLeaseResponse;
import io.testforge.workergateway.callback.model.CallbackDisposition;
import io.testforge.workergateway.callback.model.CallbackReceiptResponse;
import io.testforge.workergateway.callback.model.CallbackStatus;
import io.testforge.workergateway.callback.service.AttemptCallbackService;
import io.testforge.workergateway.callback.service.AttemptLeaseExpiredException;
import io.testforge.workergateway.callback.service.AttemptLifecycleService;
import io.testforge.workergateway.callback.service.CallbackIdempotencyConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AttemptControllerTest {

    private static final UUID ATTEMPT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID LEASE_TOKEN = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID CALLBACK_KEY = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-18T06:00:00Z");

    @Mock
    private AttemptLifecycleService lifecycleService;

    @Mock
    private AttemptCallbackService callbackService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AttemptController controller = new AttemptController(
                lifecycleService, callbackService, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AttemptCallbackExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                        new ObjectMapper()
                                .registerModule(new JavaTimeModule())
                                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                ))
                .build();
    }

    @Test
    void startEndpointReturnsRunningLease() throws Exception {
        when(lifecycleService.start(eq(ATTEMPT_ID), any())).thenReturn(new AttemptLeaseResponse(
                ATTEMPT_ID, "RUNNING", NOW.plusSeconds(30), null, NOW
        ));

        mockMvc.perform(post("/api/v1/attempts/{attemptId}/start", ATTEMPT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1","leaseToken":"%s"}
                                """.formatted(LEASE_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.state").value("RUNNING"));
    }

    @Test
    void heartbeatWithExpiredLeaseReturnsLeaseExpiredConflict() throws Exception {
        when(lifecycleService.heartbeat(eq(ATTEMPT_ID), any()))
                .thenThrow(new AttemptLeaseExpiredException(ATTEMPT_ID));

        mockMvc.perform(post("/api/v1/attempts/{attemptId}/heartbeat", ATTEMPT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId":"worker-1",
                                  "leaseToken":"%s",
                                  "progress":0.5,
                                  "at":"2026-09-18T06:00:00Z"
                                }
                                """.formatted(LEASE_TOKEN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_EXPIRED"));
    }

    @Test
    void callbackEndpointExposesDuplicateClassification() throws Exception {
        when(callbackService.callback(eq(ATTEMPT_ID), any(), eq(NOW)))
                .thenReturn(new CallbackReceiptResponse(
                        ATTEMPT_ID,
                        CALLBACK_KEY,
                        CallbackDisposition.DUPLICATE,
                        CallbackDisposition.ACCEPTED,
                        UUID.fromString("30000000-0000-4000-8000-000000000001"),
                        CallbackStatus.PASSED,
                        null,
                        NOW
                ));

        mockMvc.perform(post("/api/v1/attempts/{attemptId}/callback", ATTEMPT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCallbackJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disposition").value("DUPLICATE"))
                .andExpect(jsonPath("$.data.originalDisposition").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.resultStatus").value("PASSED"))
                .andExpect(jsonPath("$.data.receivedAt").value("2026-09-18T06:00:00Z"));
    }

    @Test
    void callbackConflictUsesIdempotencyConflictCode() throws Exception {
        when(callbackService.callback(eq(ATTEMPT_ID), any(), eq(NOW)))
                .thenThrow(new CallbackIdempotencyConflictException(ATTEMPT_ID, CALLBACK_KEY));

        mockMvc.perform(post("/api/v1/attempts/{attemptId}/callback", ATTEMPT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCallbackJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    private String validCallbackJson() {
        return """
                {
                  "schemaVersion":"1.0.0",
                  "callbackKey":"%s",
                  "attemptId":"%s",
                  "leaseToken":"%s",
                  "workerId":"worker-1",
                  "status":"PASSED",
                  "completedAt":"2026-09-18T06:00:00Z",
                  "durationMs":100,
                  "summary":"ok",
                  "artifacts":[]
                }
                """.formatted(CALLBACK_KEY, ATTEMPT_ID, LEASE_TOKEN);
    }
}
