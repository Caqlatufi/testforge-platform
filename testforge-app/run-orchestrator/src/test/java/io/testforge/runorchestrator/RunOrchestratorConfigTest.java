package io.testforge.runorchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import static org.assertj.core.api.Assertions.assertThat;

class RunOrchestratorConfigTest {

    @Test
    void configurationDeclaresTheCompleteProductionModuleBoundary() {
        assertThat(RunOrchestratorConfig.class).hasAnnotation(Configuration.class);
        assertThat(RunOrchestratorConfig.class).hasAnnotation(ComponentScan.class);
        assertThat(RunOrchestratorConfig.class).hasAnnotation(EntityScan.class);
        assertThat(RunOrchestratorConfig.class).hasAnnotation(EnableJpaRepositories.class);
    }
}
