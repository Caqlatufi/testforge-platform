package io.testforge.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.report.persistence.TestResultEntity;
import io.testforge.report.persistence.TestResultRepository;
import io.testforge.report.ctrl.RunReportController;
import io.testforge.report.service.RunReportService;
import io.testforge.report.service.CaseHistoryService;
import io.testforge.report.service.ReportEvidenceService;
import io.testforge.report.service.RunComparisonService;
import io.testforge.runorchestrator.run.service.RunTaskService;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration(proxyBeanMethods = false)
public class ReportConfig {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication
    @ComponentScan(basePackageClasses = RunReportController.class)
    @EntityScan(basePackageClasses = TestResultEntity.class)
    @EnableJpaRepositories(basePackageClasses = TestResultRepository.class)
    static class RuntimeConfig {
        @Bean
        RunReportService runReportService(
                TestResultRepository repository,
                RunTaskService runTaskService,
                ObjectMapper objectMapper
        ) {
            return new RunReportService(repository, runTaskService, objectMapper);
        }

        @Bean
        CaseHistoryService caseHistoryService(TestResultRepository repository) {
            return new CaseHistoryService(repository);
        }

        @Bean
        ReportEvidenceService reportEvidenceService(TestResultRepository repository) {
            return new ReportEvidenceService(repository);
        }

        @Bean
        RunComparisonService runComparisonService(RunTaskService runTaskService) {
            return new RunComparisonService(runTaskService);
        }
    }
}
