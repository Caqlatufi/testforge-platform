package io.testforge.report;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertSame;

class ReportConfigTest {

    @Test
    void configCanBeImportedIntoAnApplicationContext() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ReportConfig.class)) {
            assertSame(ReportConfig.class, context.getBean(ReportConfig.class).getClass());
        }
    }
}
