package io.testforge.casecatalog;

import io.testforge.casecatalog.suite.SuiteConfig;
import io.testforge.casecatalog.testcase.TestCaseDomainConfig;
import io.testforge.casecatalog.workflow.WorkflowDomainConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({
        TestCaseDomainConfig.class,
        SuiteConfig.class,
        WorkflowDomainConfig.class
})
public class CaseCatalogConfig {
}
