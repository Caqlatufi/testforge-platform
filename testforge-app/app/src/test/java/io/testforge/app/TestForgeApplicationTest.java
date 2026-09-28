package io.testforge.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.testforge.aidiagnosis.AiDiagnosisConfig;
import io.testforge.casecatalog.CaseCatalogConfig;
import io.testforge.casecatalog.suite.SuiteConfig;
import io.testforge.common.CommonConfig;
import io.testforge.dispatcher.DispatcherConfig;
import io.testforge.observability.ObservabilityConfig;
import io.testforge.projectcatalog.ProjectCatalogConfig;
import io.testforge.report.ReportConfig;
import io.testforge.runorchestrator.RunOrchestratorConfig;
import io.testforge.workergateway.WorkerGatewayConfig;
import io.testforge.testjob.TestJobConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestForgeApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void assemblesAllModuleConfigurations() {
        assertThat(applicationContext.getBeansOfType(CommonConfig.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(ProjectCatalogConfig.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(CaseCatalogConfig.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(SuiteConfig.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(RunOrchestratorConfig.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(TestJobConfig.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(DispatcherConfig.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(WorkerGatewayConfig.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(ReportConfig.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(AiDiagnosisConfig.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(ObservabilityConfig.class)).hasSize(1);
    }

    @Test
    void exposesHealthyPublicAndActuatorEndpoints() {
        ResponseEntity<JsonNode> publicHealth = restTemplate.getForEntity("/health", JsonNode.class);
        ResponseEntity<JsonNode> actuatorHealth = restTemplate.getForEntity("/actuator/health", JsonNode.class);

        assertThat(publicHealth.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicHealth.getBody()).isNotNull();
        assertThat(publicHealth.getBody().size()).isEqualTo(1);
        assertThat(publicHealth.getBody().path("status").asText()).isEqualTo("UP");
        assertThat(actuatorHealth.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actuatorHealth.getBody()).isNotNull();
        assertThat(actuatorHealth.getBody().path("status").asText()).isEqualTo("UP");
    }

    @Test
    void exposesExplicitAiProviderDegradationWithoutAffectingApplicationHealth() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                "/api/v1/reports/" + UUID.randomUUID() + "/diagnosis/status", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("data").path("available").asBoolean()).isFalse();
        assertThat(response.getBody().path("data").path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(response.getBody().path("data").path("model").asText()).isEqualTo("gpt-5.6-luna");
        assertThat(response.getBody().path("data").path("reasoningEffort").asText()).isEqualTo("high");
    }
}
