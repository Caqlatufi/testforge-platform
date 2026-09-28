package io.testforge.projectcatalog;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.context.annotation.Configuration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "io.testforge.projectcatalog",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ProjectCatalogArchitectureTest {

    @ArchTest
    static final ArchRule MODULE_DEPENDENCIES_STAY_INSIDE_PUBLIC_BOUNDARY = noClasses()
            .that().resideInAPackage("io.testforge.projectcatalog..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "io.testforge.projectcatalog..",
                    "io.testforge.common..",
                    "java..",
                    "jakarta..",
                    "org.springframework..",
                    "com.fasterxml.jackson.."
            )
            .because("project-catalog 生产代码只能依赖自身、common、JDK、Jakarta、Spring 与 JSON 序列化边界");

    @ArchTest
    static final ArchRule ROOT_PACKAGE_ONLY_EXPOSES_MODULE_CONFIGURATION = classes()
            .that().resideInAPackage("io.testforge.projectcatalog")
            .should().haveSimpleNameEndingWith("Config")
            .andShould().beAnnotatedWith(Configuration.class)
            .because("模块根包只保留供 app 导入的装配入口");

    @ArchTest
    static final ArchRule CONTROLLERS_DO_NOT_BYPASS_SERVICES = noClasses()
            .that().resideInAPackage("io.testforge.projectcatalog.ctrl..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.testforge.projectcatalog.repo..",
                    "io.testforge.projectcatalog.entity.."
            )
            .because("控制器必须通过公开 Service 访问业务与数据")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule REPOSITORIES_STAY_PRIVATE_TO_SERVICES_AND_CONFIGURATION = noClasses()
            .that().resideOutsideOfPackages(
                    "io.testforge.projectcatalog.service..",
                    "io.testforge.projectcatalog"
            )
            .should().dependOnClassesThat().resideInAPackage("io.testforge.projectcatalog.repo..")
            .because("Repo 只能被本模块 Service 或配置访问")
            .allowEmptyShould(true);
}
