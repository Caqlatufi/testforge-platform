package io.testforge.casecatalog.suite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.suite.entity.TestSuiteEntity;
import io.testforge.casecatalog.suite.repo.TestSuiteRepository;
import io.testforge.casecatalog.suite.service.TestSuiteService;
import io.testforge.casecatalog.testcase.service.TestCaseService;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 无序 TestSuite 资产的装配入口，由 {@code CaseCatalogConfig} 与 Case 子域统一导入。
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = "io.testforge.casecatalog.suite.ctrl")
@EntityScan(basePackageClasses = TestSuiteEntity.class)
@EnableJpaRepositories(basePackageClasses = TestSuiteRepository.class)
public class SuiteConfig {

    @Bean
    TestSuiteService testSuiteService(
            TestSuiteRepository repository,
            ProjectCatalogService projectCatalogService,
            TestCaseService testCaseService,
            ObjectMapper objectMapper
    ) {
        return new TestSuiteService(repository, projectCatalogService, testCaseService, objectMapper);
    }
}
