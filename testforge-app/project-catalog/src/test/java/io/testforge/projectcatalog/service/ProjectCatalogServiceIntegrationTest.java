package io.testforge.projectcatalog.service;

import io.testforge.projectcatalog.ProjectCatalogConfig;
import io.testforge.projectcatalog.model.CreateEnvironmentCommand;
import io.testforge.projectcatalog.model.CreateProjectCommand;
import io.testforge.projectcatalog.model.CreateTargetCommand;
import io.testforge.projectcatalog.model.EnvironmentPlatform;
import io.testforge.projectcatalog.model.TargetType;
import io.testforge.projectcatalog.repo.ProjectEnvironmentRepository;
import io.testforge.projectcatalog.repo.ProjectRepository;
import io.testforge.projectcatalog.repo.ProjectTargetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = ProjectCatalogServiceIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:project_catalog;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=BINARY"
        }
)
@ActiveProfiles("test")
@Transactional
class ProjectCatalogServiceIntegrationTest {

    @Autowired
    private ProjectCatalogService service;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectTargetRepository targetRepository;

    @Autowired
    private ProjectEnvironmentRepository environmentRepository;

    @Test
    void shouldCreateGlobalEnvironmentWithoutProjectOwnership() {
        var environment = service.createEnvironment(new CreateEnvironmentCommand(
                "Windows VM Shared", "http://127.0.0.1", "windows-vm-shared",
                EnvironmentPlatform.WINDOWS, "windows", 2, true, true,
                Map.of(), Map.of("admin", "local-env://windows/admin")
        ));

        assertThat(environment.targetId()).isNull();
        assertThat(environment.platform()).isEqualTo(EnvironmentPlatform.WINDOWS);
        assertThat(environment.resourcePoolKey()).isEqualTo("windows");
        assertThat(service.listEnvironments(true, EnvironmentPlatform.WINDOWS))
                .extracting(item -> item.id()).contains(environment.id());
    }

    @Test
    void shouldPersistAllTargetTypesAndReturnProjectAggregate() {
        var project = service.createProject(new CreateProjectCommand("技能测试", "skill-test"));

        var http = service.createTarget(project.id(), new CreateTargetCommand("HTTP API", TargetType.HTTP_SERVICE));
        var web = service.createTarget(project.id(), new CreateTargetCommand("Web Console", TargetType.WEB));
        var desktop = service.createTarget(project.id(), new CreateTargetCommand("Windows Client", TargetType.DESKTOP));
        var mobile = service.createTarget(project.id(), new CreateTargetCommand("Android Client", TargetType.MOBILE));

        service.createEnvironment(http.id(), environment("联调", "https://api.example.test", Map.of("locale", "zh-CN")));
        service.createEnvironment(web.id(), environment("预发布", "https://web.example.test", Map.of("browser", "chromium")));
        service.createEnvironment(desktop.id(), environment("Windows", "app://skill-sandbox/windows", Map.of("platform", "windows")));
        service.createEnvironment(mobile.id(), environment("Android", "android://skill-sandbox/main", Map.of("platform", "android")));

        var aggregate = service.getProject(project.id());

        assertThat(aggregate.targets()).extracting(target -> target.type())
                .containsExactly(
                        TargetType.HTTP_SERVICE,
                        TargetType.WEB,
                        TargetType.DESKTOP,
                        TargetType.MOBILE
                );
        assertThat(aggregate.targets()).allSatisfy(target -> assertThat(target.environments()).isEmpty());
        assertThat(service.listEnvironments(null, null)).hasSize(4);
        assertThat(projectRepository.count()).isEqualTo(1);
        assertThat(targetRepository.count()).isEqualTo(4);
        assertThat(environmentRepository.count()).isEqualTo(4);
    }

    @Test
    void shouldMakeSameNaturalAssetCreationRetrySafeAndRejectConflictingContent() {
        var first = service.createProject(new CreateProjectCommand("演示项目", "demo-project"));
        var retried = service.createProject(new CreateProjectCommand("演示项目", "demo-project"));

        assertThat(retried.id()).isEqualTo(first.id());
        assertThat(projectRepository.count()).isEqualTo(1);
        assertThatThrownBy(() -> service.createProject(new CreateProjectCommand("另一个项目", "demo-project")))
                .isInstanceOf(ProjectCatalogConflictException.class)
                .hasMessageContaining("项目编码已存在");
    }

    @Test
    void shouldUseProjectUuidAsInternalCodeWhenClientOmitsCode() {
        var project = service.createProject(new CreateProjectCommand("自动标识项目", null));

        assertThat(project.code()).isEqualTo(project.id().toString());

        var updated = service.updateProject(
                project.id(), new CreateProjectCommand("重命名后的项目", null)
        );
        assertThat(updated.code()).isEqualTo(project.id().toString());
    }

    @Test
    void shouldRejectMissingParentsAndSensitivePlaintextConfig() {
        assertThatThrownBy(() -> service.createTarget(
                UUID.randomUUID(),
                new CreateTargetCommand("孤立目标", TargetType.WEB)
        )).isInstanceOf(ProjectCatalogNotFoundException.class);

        var project = service.createProject(new CreateProjectCommand("安全配置", "secure-config"));
        var target = service.createTarget(project.id(), new CreateTargetCommand("API", TargetType.HTTP_SERVICE));

        assertThatThrownBy(() -> service.createEnvironment(
                target.id(),
                environment("测试", "https://secure.example.test", Map.of("accessToken", "plaintext"))
        )).isInstanceOf(ProjectCatalogValidationException.class)
                .hasMessageContaining("secretRefs");
        assertThat(environmentRepository.count()).isZero();
    }

    @Test
    void shouldListAndUpdateProjectTargetAndEnvironment() {
        var project = service.createProject(new CreateProjectCommand("旧项目", "old-project"));
        var target = service.createTarget(project.id(), new CreateTargetCommand(
                "旧目标", TargetType.WEB, "https://example.test/old.git", "main"
        ));
        var environment = service.createEnvironment(
                target.id(), environment("旧环境", "https://old.example.test", Map.of("locale", "zh-CN"))
        );

        var updatedProject = service.updateProject(
                project.id(), new CreateProjectCommand("新项目", "new-project")
        );
        var updatedTarget = service.updateTarget(
                target.id(), new CreateTargetCommand(
                        "新目标", TargetType.HTTP_SERVICE, "https://example.test/new.git", "develop"
                )
        );
        var updatedEnvironment = service.updateEnvironment(
                environment.id(), environment("新环境", "https://new.example.test", Map.of("locale", "en-US"))
        );

        assertThat(service.listProjects()).extracting(item -> item.name()).contains("新项目");
        assertThat(updatedProject.code()).isEqualTo("new-project");
        assertThat(updatedTarget.type()).isEqualTo(TargetType.HTTP_SERVICE);
        assertThat(updatedTarget.repositoryUrl()).isEqualTo("https://example.test/new.git");
        assertThat(updatedTarget.defaultBranch()).isEqualTo("develop");
        assertThat(updatedEnvironment.endpoint()).isEqualTo("https://new.example.test");
        assertThat(service.getProject(project.id()).targets().getFirst().environments()).isEmpty();
        assertThat(service.requireEnvironmentView(environment.id()).name()).isEqualTo("新环境");
    }

    @Test
    void shouldRequireRepositoryAndDefaultBranchTogether() {
        var project = service.createProject(new CreateProjectCommand("版本项目", "revision-project"));

        assertThatThrownBy(() -> service.createTarget(
                project.id(),
                new CreateTargetCommand("不完整目标", TargetType.WEB, "https://example.test/repo.git", null)
        )).isInstanceOf(ProjectCatalogValidationException.class)
                .hasMessageContaining("同时配置");
    }

    private CreateEnvironmentCommand environment(String name, String endpoint, Map<String, Object> config) {
        return new CreateEnvironmentCommand(name, endpoint, config, Map.of("auth", "vault://testforge/auth"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ProjectCatalogConfig.class)
    static class TestApplication {
    }
}
