package io.testforge.runorchestrator.ctrl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.runorchestrator.job.TestJobLaunchService;
import io.testforge.runorchestrator.job.TestJobRunQueryService;
import io.testforge.runorchestrator.run.service.RunStateConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TestJobRunControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldExposeActiveExecutionConflictAsHttp409() throws Exception {
        TestJobLaunchService launch = mock(TestJobLaunchService.class);
        UUID taskId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        when(launch.launch(taskId, requestKey))
                .thenThrow(new RunStateConflictException("测试任务已有活动执行 Attempt"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                        new TestJobRunController(launch, mock(TestJobRunQueryService.class)))
                .setControllerAdvice(new RunExceptionHandler())
                .build();

        mockMvc.perform(post("/api/v1/test-jobs/{id}/execute", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("requestKey", requestKey))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.message").value("测试任务已有活动执行 Attempt"));
    }
}
