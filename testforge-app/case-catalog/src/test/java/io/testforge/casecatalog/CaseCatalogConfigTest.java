package io.testforge.casecatalog;

import io.testforge.casecatalog.suite.SuiteConfig;
import io.testforge.casecatalog.testcase.TestCaseDomainConfig;
import io.testforge.casecatalog.workflow.WorkflowDomainConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CaseCatalogConfigTest {

    @Test
    void rootConfigurationImportsAllAssetBoundaries() {
        Import importedDomains = CaseCatalogConfig.class.getAnnotation(Import.class);

        assertThat(importedDomains).isNotNull();
        assertThat(Arrays.asList(importedDomains.value()))
                .containsExactlyInAnyOrder(
                        TestCaseDomainConfig.class,
                        SuiteConfig.class,
                        WorkflowDomainConfig.class
                );
    }
}
