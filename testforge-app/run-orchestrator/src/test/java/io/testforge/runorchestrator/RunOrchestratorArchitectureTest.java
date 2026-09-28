package io.testforge.runorchestrator;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class RunOrchestratorArchitectureTest {

    private static final String MODULE_PACKAGE = "io.testforge.runorchestrator";

    private final JavaClasses moduleClasses = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(MODULE_PACKAGE);

    @Test
    void classesStayInsideDeclaredModulePackages() {
        ArchRule rule = classes()
                .should().resideInAnyPackage(
                        MODULE_PACKAGE,
                        MODULE_PACKAGE + ".ctrl..",
                        MODULE_PACKAGE + ".entity..",
                        MODULE_PACKAGE + ".event..",
                        MODULE_PACKAGE + ".model..",
                        MODULE_PACKAGE + ".port..",
                        MODULE_PACKAGE + ".repo..",
                        MODULE_PACKAGE + ".service..",
                        MODULE_PACKAGE + ".comparison..",
                        MODULE_PACKAGE + ".job..",
                        MODULE_PACKAGE + ".run..",
                        MODULE_PACKAGE + ".task..");

        rule.check(moduleClasses);
    }

    @Test
    void moduleDoesNotDependOnDownstreamBusinessModules() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(MODULE_PACKAGE + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.testforge.app..",
                        "io.testforge.dispatcher..",
                        "io.testforge.workergateway..",
                        "io.testforge.report..",
                        "io.testforge.aidiagnosis..",
                        "io.testforge.observability..");

        rule.check(moduleClasses);
    }
}
