package io.testforge.app;

import io.testforge.aidiagnosis.AiDiagnosisConfig;
import io.testforge.casecatalog.CaseCatalogConfig;
import io.testforge.cicdgateway.CicdGatewayConfig;
import io.testforge.common.CommonConfig;
import io.testforge.dispatcher.DispatcherConfig;
import io.testforge.observability.ObservabilityConfig;
import io.testforge.projectcatalog.ProjectCatalogConfig;
import io.testforge.report.ReportConfig;
import io.testforge.runorchestrator.RunOrchestratorConfig;
import io.testforge.workergateway.WorkerGatewayConfig;
import io.testforge.testjob.TestJobConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
        CommonConfig.class,
        ProjectCatalogConfig.class,
        CaseCatalogConfig.class,
        CicdGatewayConfig.class,
        TestJobConfig.class,
        RunOrchestratorConfig.class,
        DispatcherConfig.class,
        WorkerGatewayConfig.class,
        ReportConfig.class,
        AiDiagnosisConfig.class,
        ObservabilityConfig.class,
        SimpleMigrationBootstrapConfig.class
})
public class TestForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestForgeApplication.class, args);
    }
}
