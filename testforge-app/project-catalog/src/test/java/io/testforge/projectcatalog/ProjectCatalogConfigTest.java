package io.testforge.projectcatalog;

import io.testforge.projectcatalog.entity.TestProjectEntity;
import io.testforge.projectcatalog.repo.ProjectRepository;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCatalogConfigTest {

    @Test
    void shouldExposePersistenceAndServiceAssemblyFromPublicConfiguration() {
        var entityScan = ProjectCatalogConfig.class.getAnnotation(EntityScan.class);
        var repositoryScan = ProjectCatalogConfig.class.getAnnotation(EnableJpaRepositories.class);

        assertNotNull(entityScan);
        assertNotNull(repositoryScan);
        assertArrayEquals(new Class<?>[]{TestProjectEntity.class}, entityScan.basePackageClasses());
        assertArrayEquals(new Class<?>[]{ProjectRepository.class}, repositoryScan.basePackageClasses());
        assertTrue(Arrays.stream(ProjectCatalogConfig.class.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(Bean.class)
                        && method.getReturnType().equals(ProjectCatalogService.class)));
    }

    @Test
    void shouldOnlyComponentScanModuleControllers() {
        var componentScan = ProjectCatalogConfig.class.getAnnotation(ComponentScan.class);

        assertNotNull(componentScan);
        assertArrayEquals(
                new String[]{"io.testforge.projectcatalog.ctrl"},
                componentScan.basePackages()
        );
    }
}
