package io.testforge.aidiagnosis;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertSame;

class AiDiagnosisConfigTest {

    @Test
    void configCanBeImportedIntoAnApplicationContext() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(AiDiagnosisConfig.class)) {
            assertSame(AiDiagnosisConfig.class, context.getBean(AiDiagnosisConfig.class).getClass());
        }
    }
}
