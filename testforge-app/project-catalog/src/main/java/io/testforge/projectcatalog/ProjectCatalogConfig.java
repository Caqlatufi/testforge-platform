package io.testforge.projectcatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.projectcatalog.entity.TestProjectEntity;
import io.testforge.projectcatalog.repo.ProjectEnvironmentRepository;
import io.testforge.projectcatalog.repo.ProjectRepository;
import io.testforge.projectcatalog.repo.ProjectTargetRepository;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import io.testforge.projectcatalog.revision.GitRevisionResolver;
import io.testforge.projectcatalog.revision.RevisionResolver;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = "io.testforge.projectcatalog.ctrl")
@EntityScan(basePackageClasses = TestProjectEntity.class)
@EnableJpaRepositories(basePackageClasses = ProjectRepository.class)
public class ProjectCatalogConfig {

    @Bean
    RevisionResolver revisionResolver() {
        return new GitRevisionResolver();
    }

    @Bean
    ProjectCatalogService projectCatalogService(
            ProjectRepository projectRepository,
            ProjectTargetRepository targetRepository,
            ProjectEnvironmentRepository environmentRepository,
            ObjectMapper objectMapper,
            RevisionResolver revisionResolver
    ) {
        return new ProjectCatalogService(
                projectRepository,
                targetRepository,
                environmentRepository,
                objectMapper,
                revisionResolver
        );
    }
}
