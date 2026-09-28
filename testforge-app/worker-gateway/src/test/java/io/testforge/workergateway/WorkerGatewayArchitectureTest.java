package io.testforge.workergateway;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.context.annotation.Configuration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "io.testforge.workergateway",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class WorkerGatewayArchitectureTest {

    @ArchTest
    static final ArchRule ports_do_not_depend_on_adapters = noClasses()
            .that().resideInAPackage("..port..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule inbound_and_outbound_adapters_are_decoupled = noClasses()
            .that().resideInAPackage("..adapter.inbound..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.outbound..");

    @ArchTest
    static final ArchRule outbound_and_inbound_adapters_are_decoupled = noClasses()
            .that().resideInAPackage("..adapter.outbound..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.inbound..");

    @ArchTest
    static final ArchRule module_does_not_access_foreign_repositories = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.testforge.projectcatalog.repo..",
                    "io.testforge.casecatalog.repo..",
                    "io.testforge.runorchestrator.repo..",
                    "io.testforge.dispatcher.repo..",
                    "io.testforge.report.repo..",
                    "io.testforge.aidiagnosis.repo..",
                    "io.testforge.observability.repo.."
            );

    @ArchTest
    static final ArchRule spring_configuration_stays_at_module_root = classes()
            .that().areAnnotatedWith(Configuration.class)
            .should().resideInAPackage("io.testforge.workergateway");
}
