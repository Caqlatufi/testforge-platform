package io.testforge.casecatalog;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "io.testforge.casecatalog")
class CaseCatalogArchitectureTest {

    @ArchTest
    static final ArchRule CASE_DOMAIN_DOES_NOT_DEPEND_ON_WORKFLOW = noClasses()
            .that().resideInAPackage("io.testforge.casecatalog.testcase..")
            .should().dependOnClassesThat().resideInAPackage("io.testforge.casecatalog.workflow..")
            .because("原子 Case 资产必须独立于 Workflow 编排");

    @ArchTest
    static final ArchRule DOMAIN_PACKAGES_ARE_FREE_OF_CYCLES = slices()
            .matching("io.testforge.casecatalog.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule DOMAIN_CONFIGURATIONS_STAY_AT_DOMAIN_ROOT = classes()
            .that().haveSimpleNameEndingWith("DomainConfig")
            .should().resideInAnyPackage(
                    "io.testforge.casecatalog.testcase",
                    "io.testforge.casecatalog.workflow"
            );

    @ArchTest
    static final ArchRule PROJECT_CATALOG_INTERNALS_ARE_NOT_ACCESSED = noClasses()
            .that().resideInAPackage("io.testforge.casecatalog..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.testforge.projectcatalog.ctrl..",
                    "io.testforge.projectcatalog.entity..",
                    "io.testforge.projectcatalog.repo.."
            )
            .because("跨模块协作只能使用 project-catalog 的公开查询 Service、Model 或 Event");
}
