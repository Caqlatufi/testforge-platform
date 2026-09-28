package io.testforge.casecatalog.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.suite.service.TestSuiteService;
import io.testforge.casecatalog.testcase.service.TestCaseService;
import io.testforge.casecatalog.workflow.compile.repo.PublishedWorkflowVersionRepository;
import io.testforge.casecatalog.workflow.compile.service.WorkflowPublishService;
import io.testforge.casecatalog.workflow.ctrl.TestWorkflowController;
import io.testforge.casecatalog.workflow.repo.TestWorkflowRepository;
import io.testforge.casecatalog.workflow.service.TestWorkflowService;
import io.testforge.casecatalog.workflow.service.WorkflowReferenceResolver;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Workflow 草稿、发布版本、DAG 校验与执行快照子域的装配入口。
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = TestWorkflowController.class)
@EntityScan(basePackages = "io.testforge.casecatalog.workflow")
@EnableJpaRepositories(basePackageClasses = {
        TestWorkflowRepository.class,
        PublishedWorkflowVersionRepository.class
})
public class WorkflowDomainConfig {

    @Bean
    WorkflowPublishService workflowPublishService(
            PublishedWorkflowVersionRepository repository,
            ObjectMapper objectMapper
    ) {
        return new WorkflowPublishService(repository, objectMapper);
    }

    @Bean
    WorkflowReferenceResolver workflowReferenceResolver(
            TestCaseService testCaseService,
            TestSuiteService testSuiteService,
            WorkflowPublishService publishService
    ) {
        return new WorkflowReferenceResolver(testCaseService, testSuiteService, publishService);
    }

    @Bean
    TestWorkflowService testWorkflowService(
            TestWorkflowRepository repository,
            ProjectCatalogService projectCatalogService,
            ObjectMapper objectMapper,
            WorkflowReferenceResolver referenceResolver,
            WorkflowPublishService publishService
    ) {
        return new TestWorkflowService(
                repository,
                projectCatalogService,
                objectMapper,
                referenceResolver,
                publishService
        );
    }
}
