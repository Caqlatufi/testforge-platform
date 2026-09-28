package io.testforge.observability;

import io.testforge.observability.event.ExecutionEventEntity;
import io.testforge.observability.event.ExecutionEventRepository;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = "io.testforge.observability.event")
@EntityScan(basePackageClasses = ExecutionEventEntity.class)
@EnableJpaRepositories(basePackageClasses = ExecutionEventRepository.class)
public class ObservabilityConfig {
}
