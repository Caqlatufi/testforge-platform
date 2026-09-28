package io.testforge.casecatalog.suite;

import io.testforge.casecatalog.suite.entity.TestSuiteEntity;
import io.testforge.casecatalog.suite.repo.TestSuiteRepository;
import io.testforge.casecatalog.suite.service.TestSuiteService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuiteConfigTest {

    @Test
    void shouldExposeOnlySuitePersistenceControllersAndServiceAssembly() {
        var entityScan = SuiteConfig.class.getAnnotation(EntityScan.class);
        var repositoryScan = SuiteConfig.class.getAnnotation(EnableJpaRepositories.class);
        var componentScan = SuiteConfig.class.getAnnotation(ComponentScan.class);

        assertNotNull(entityScan);
        assertNotNull(repositoryScan);
        assertNotNull(componentScan);
        assertArrayEquals(new Class<?>[]{TestSuiteEntity.class}, entityScan.basePackageClasses());
        assertArrayEquals(new Class<?>[]{TestSuiteRepository.class}, repositoryScan.basePackageClasses());
        assertArrayEquals(new String[]{"io.testforge.casecatalog.suite.ctrl"}, componentScan.basePackages());
        assertTrue(Arrays.stream(SuiteConfig.class.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(Bean.class)
                        && method.getReturnType().equals(TestSuiteService.class)));
    }
}
