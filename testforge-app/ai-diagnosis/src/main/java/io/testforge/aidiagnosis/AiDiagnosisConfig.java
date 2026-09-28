package io.testforge.aidiagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.aidiagnosis.config.AiDiagnosisProperties;
import io.testforge.aidiagnosis.ctrl.AiDiagnosisController;
import io.testforge.aidiagnosis.provider.CodexCliDiagnosisProvider;
import io.testforge.aidiagnosis.provider.EvidencePromptFactory;
import io.testforge.aidiagnosis.repo.AiDiagnosisRecordEntity;
import io.testforge.aidiagnosis.repo.AiDiagnosisRecordRepository;
import io.testforge.aidiagnosis.service.AiDiagnosisService;
import io.testforge.report.port.inbound.ReportEvidenceQueryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration(proxyBeanMethods = false)
public class AiDiagnosisConfig {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication
    @ComponentScan(basePackageClasses = AiDiagnosisController.class)
    @EntityScan(basePackageClasses = AiDiagnosisRecordEntity.class)
    @EnableJpaRepositories(basePackageClasses = AiDiagnosisRecordRepository.class)
    @EnableConfigurationProperties(AiDiagnosisProperties.class)
    static class RuntimeConfig {
        @Bean
        EvidencePromptFactory evidencePromptFactory(AiDiagnosisProperties properties) {
            return new EvidencePromptFactory(properties);
        }

        @Bean
        CodexCliDiagnosisProvider codexCliDiagnosisProvider(AiDiagnosisProperties properties,
                                                            EvidencePromptFactory promptFactory,
                                                            ObjectMapper objectMapper) {
            return new CodexCliDiagnosisProvider(properties, promptFactory, objectMapper);
        }

        @Bean
        AiDiagnosisService aiDiagnosisService(ReportEvidenceQueryPort evidenceQuery,
                                               CodexCliDiagnosisProvider provider,
                                               AiDiagnosisRecordRepository repository,
                                               EvidencePromptFactory promptFactory,
                                               AiDiagnosisProperties properties,
                                               ObjectMapper objectMapper) {
            return new AiDiagnosisService(evidenceQuery, provider, repository, promptFactory, properties, objectMapper);
        }
    }
}
