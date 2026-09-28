package io.testforge.cicdgateway;

import io.testforge.cicdgateway.catalog.service.JenkinsConnectionProperties;
import io.testforge.cicdgateway.deployment.entity.DeploymentProfileEntity;
import io.testforge.cicdgateway.deployment.repo.DeploymentProfileRepository;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = "io.testforge.cicdgateway")
@EntityScan(basePackageClasses = DeploymentProfileEntity.class)
@EnableJpaRepositories(basePackageClasses = DeploymentProfileRepository.class)
@EnableScheduling
@EnableConfigurationProperties(JenkinsConnectionProperties.class)
public class CicdGatewayConfig {
}
