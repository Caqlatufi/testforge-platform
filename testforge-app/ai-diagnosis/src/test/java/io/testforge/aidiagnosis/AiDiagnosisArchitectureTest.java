package io.testforge.aidiagnosis;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class AiDiagnosisArchitectureTest {

    @Test
    void portsAreInterfaces() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Port")
                .or().haveSimpleNameEndingWith("UseCase")
                .should().beInterfaces()
                .andShould().resideInAPackage("..port..");

        rule.check(new ClassFileImporter().importPackages("io.testforge.aidiagnosis"));
    }

    @Test
    void contractsStayIndependentFromSpring() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("..port..", "..model..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..");

        rule.check(new ClassFileImporter().importPackages("io.testforge.aidiagnosis"));
    }

    @Test
    void aiOnlyUsesThePublicReadOnlyReportBoundary() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.testforge.aidiagnosis..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.testforge.report.ctrl..",
                        "io.testforge.report.entity..",
                        "io.testforge.report.repo..",
                        "io.testforge.report.service..",
                        "io.testforge.report.event..",
                        "io.testforge.report.port.outbound..");

        rule.check(new ClassFileImporter().importPackages("io.testforge.aidiagnosis"));
    }
}
