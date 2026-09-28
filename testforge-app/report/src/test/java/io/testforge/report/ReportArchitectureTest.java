package io.testforge.report;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ReportArchitectureTest {

    @Test
    void portsAreInterfaces() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Port")
                .should().beInterfaces()
                .andShould().resideInAPackage("..port..");

        rule.check(new ClassFileImporter().importPackages("io.testforge.report"));
    }

    @Test
    void contractsStayIndependentFromSpring() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("..port..", "..model..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..");

        rule.check(new ClassFileImporter().importPackages("io.testforge.report"));
    }

    @Test
    void reportDoesNotReachAcrossModuleRepositoriesOrDependOnAi() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.testforge.report..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.testforge.casecatalog.repo..",
                        "io.testforge.runorchestrator.repo..",
                        "io.testforge.aidiagnosis..");

        rule.check(new ClassFileImporter().importPackages("io.testforge.report"));
    }
}
