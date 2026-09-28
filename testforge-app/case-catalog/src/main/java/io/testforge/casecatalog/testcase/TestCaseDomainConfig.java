package io.testforge.casecatalog.testcase;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.testcase.asset.ctrl.CaseAssetController;
import io.testforge.casecatalog.testcase.asset.entity.CaseAssetEntity;
import io.testforge.casecatalog.testcase.asset.repo.CaseAssetRepository;
import io.testforge.casecatalog.testcase.asset.service.CaseAssetService;
import io.testforge.casecatalog.testcase.ctrl.TestCaseController;
import io.testforge.casecatalog.testcase.entity.TestCaseEntity;
import io.testforge.casecatalog.testcase.repo.TestCaseRepository;
import io.testforge.casecatalog.testcase.repo.TestScriptVersionRepository;
import io.testforge.casecatalog.testcase.service.TestCaseService;
import io.testforge.casecatalog.testcase.service.CaseDefinitionParser;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 原子用例与不可变脚本版本子域的装配入口。
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = {TestCaseController.class, CaseAssetController.class})
@EntityScan(basePackageClasses = {TestCaseEntity.class, CaseAssetEntity.class})
@EnableJpaRepositories(basePackageClasses = {TestCaseRepository.class, CaseAssetRepository.class})
public class TestCaseDomainConfig {

    @Bean
    TestCaseService testCaseService(
            TestCaseRepository testCaseRepository,
            TestScriptVersionRepository scriptVersionRepository,
            ProjectCatalogService projectCatalogService,
            ObjectMapper objectMapper,
            CaseDefinitionParser definitionParser
    ) {
        return new TestCaseService(
                testCaseRepository,
                scriptVersionRepository,
                projectCatalogService,
                objectMapper,
                definitionParser
        );
    }

    @Bean
    CaseAssetService caseAssetService(
            CaseAssetRepository repository,
            ProjectCatalogService projectCatalogService
    ) {
        return new CaseAssetService(repository, projectCatalogService);
    }

    @Bean
    CaseDefinitionParser caseDefinitionParser(CaseAssetService assetService) {
        return new CaseDefinitionParser(assetService);
    }
}
