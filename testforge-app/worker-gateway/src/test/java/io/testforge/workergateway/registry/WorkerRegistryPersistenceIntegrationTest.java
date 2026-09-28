package io.testforge.workergateway.registry;

import io.testforge.workergateway.WorkerGatewayConfig;
import io.testforge.workergateway.registry.model.RegisterWorkerCommand;
import io.testforge.workergateway.registry.model.WorkerStatus;
import io.testforge.workergateway.registry.repo.WorkerNodeRepository;
import io.testforge.workergateway.registry.service.WorkerRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:worker_registry;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "testforge.worker-gateway.registry.offline-timeout=15s"
})
@AutoConfigureMockMvc
class WorkerRegistryPersistenceIntegrationTest {

    @Autowired
    private WorkerRegistryService service;

    @Autowired
    private WorkerNodeRepository repository;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void persistsOneNodeWhenRegistrationIsRepeatedAndCapabilitiesChange() {
        var first = service.register(new RegisterWorkerCommand(
                "worker-db-01", "1.0", Set.of("PYTEST_HTTP", "LINUX", "HTTP"), 2
        ));
        var updated = service.register(new RegisterWorkerCommand(
                "worker-db-01", "1.1", Set.of("AIRTEST", "WINDOWS", "WINDOWS_UI"), 1
        ));

        assertThat(updated.instanceId()).isEqualTo(first.instanceId());
        assertThat(updated.capabilities()).containsExactlyInAnyOrder("AIRTEST", "WINDOWS", "WINDOWS_UI");
        assertThat(updated.status()).isEqualTo(WorkerStatus.ONLINE);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void exposesRegistrationHeartbeatMatchingAndOnlineQueries() throws Exception {
        mockMvc.perform(post("/api/v1/workers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId": "worker-api-01",
                                  "protocolVersion": "1.2",
                                  "capabilities": ["PYTEST_HTTP", "LINUX", "HTTP"],
                                  "maxConcurrency": 4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.workerId").value("worker-api-01"))
                .andExpect(jsonPath("$.data.status").value("ONLINE"));

        mockMvc.perform(get("/api/v1/workers/match")
                        .queryParam("protocolVersion", "1.1")
                        .queryParam("runner", "pytest-http")
                        .queryParam("platform", "LINUX")
                        .queryParam("requiredFeatures", "HTTP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].workerId").value("worker-api-01"));

        mockMvc.perform(post("/api/v1/workers/worker-api-01/heartbeat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ONLINE"));

        mockMvc.perform(get("/api/v1/workers").queryParam("status", "ONLINE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(WorkerGatewayConfig.class)
    static class TestApplication {
    }
}
