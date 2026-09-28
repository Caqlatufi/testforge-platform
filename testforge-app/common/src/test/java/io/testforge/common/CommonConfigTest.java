package io.testforge.common;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CommonConfigTest {

    @Test
    void loadsWithoutAnyBusinessModule() {
        assertDoesNotThrow(() -> {
            try (var context = new AnnotationConfigApplicationContext(CommonConfig.class)) {
                assertNotNull(context.getBean(CommonConfig.class));
            }
        });
    }
}
