package io.testforge.cicdgateway.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.testforge.cicdgateway.catalog.model.PipelineRef;
import io.testforge.cicdgateway.deployment.entity.DeploymentProfileEntity;
import io.testforge.cicdgateway.deployment.repo.DeploymentProfileRepository;
import io.testforge.cicdgateway.provider.CicdCredentialResolver;
import io.testforge.projectcatalog.model.ProjectState;
import io.testforge.projectcatalog.model.ProjectView;
import io.testforge.projectcatalog.model.TargetType;
import io.testforge.projectcatalog.model.TargetView;
import io.testforge.projectcatalog.model.EnvironmentView;
import io.testforge.projectcatalog.model.EnvironmentPlatform;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PipelineCatalogServiceTest {
    @TempDir
    Path tempDir;
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void discoversFolderPipelineUsesTestForgeEnvironmentAndMaterializesExecutionProfile() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/job/team/api/json", exchange -> respond(exchange, """
                {"jobs":[
                  {"name":"deploy","fullName":"team/deploy","_class":"hudson.model.FreeStyleProject","color":"blue","buildable":true},
                  {"name":"other","fullName":"team/other","_class":"hudson.model.FreeStyleProject","color":"blue","buildable":true}
                ]}
                """, "application/json"));
        server.createContext("/job/team/job/deploy/config.xml", exchange -> respond(exchange, """
                <flow-definition>
                  <properties><hudson.model.ParametersDefinitionProperty><parameterDefinitions>
                    <hudson.model.ChoiceParameterDefinition>
                      <name>DEPLOY_ENVIRONMENT</name>
                      <choices><a class="java.util.Arrays$ArrayList"><a><string>test</string><string>staging</string></a></a></choices>
                    </hudson.model.ChoiceParameterDefinition>
                  </parameterDefinitions></hudson.model.ParametersDefinitionProperty></properties>
                </flow-definition>
                """, "application/xml"));
        server.start();

        UUID projectId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ProjectView project = new ProjectView(projectId, "Demo", "demo", ProjectState.ACTIVE,
                TargetType.HTTP_SERVICE, "https://git.example/demo.git", "main", "JENKINS",
                baseUrl, "team", "jenkins-local",
                List.of(new TargetView(targetId, projectId, "Demo", TargetType.HTTP_SERVICE,
                        "https://git.example/demo.git", "main", List.of(
                        new EnvironmentView(environmentId, targetId, "Staging", null, "staging", 2,
                                true, false, java.util.Map.of(), java.util.Map.of())))));
        ProjectCatalogService projects = mock(ProjectCatalogService.class);
        when(projects.requireProjectView(projectId)).thenReturn(project);
        when(projects.requireExecutionTarget(projectId)).thenReturn(project.targets().getFirst());
        when(projects.listEnvironments(true, null)).thenReturn(project.targets().getFirst().environments());
        DeploymentProfileRepository profiles = mock(DeploymentProfileRepository.class);
        DeploymentProfileEntity binding = mock(DeploymentProfileEntity.class);
        UUID bindingId = UUID.randomUUID();
        when(binding.getId()).thenReturn(bindingId);
        when(binding.getJobName()).thenReturn("team/deploy");
        when(binding.isEnabled()).thenReturn(true);
        when(profiles.findAllByTargetIdOrderByCreatedAtAsc(targetId)).thenReturn(List.of(binding));
        when(profiles.findByTargetIdAndJobName(targetId, "team/deploy")).thenReturn(Optional.of(binding));
        when(profiles.saveAndFlush(binding)).thenReturn(binding);
        CicdCredentialResolver credentials = ignored -> new CicdCredentialResolver.Credential("testforge", "token");
        JenkinsConnectionProperties connection = new JenkinsConnectionProperties();
        connection.setServerUrl(baseUrl);
        connection.setFolder("team");
        connection.setCredentialRef("jenkins-local");
        var service = new PipelineCatalogService(profiles, projects, credentials,
                new ObjectMapper(), connection, HttpClient.newHttpClient());

        var visiblePipelines = service.list(projectId);
        assertThat(visiblePipelines).extracting(PipelineRef::jobName).containsExactly("team/deploy");
        var pipeline = visiblePipelines.getFirst();
        assertThat(pipeline.name()).isEqualTo("deploy");
        var selection = service.validate(projectId, pipeline.externalId().toString(), environmentId.toString());
        assertThat(selection.environment().name()).isEqualTo("Staging");
        assertThat(selection.environment().providerKey()).isEqualTo("staging");
        assertThat(selection.deploymentProfileId()).isEqualTo(bindingId);
        assertThat(selection.pipeline().revision()).startsWith("sha256:");
    }

    @Test
    void routesPlatformRequestToLargestInternalPoolWithoutExposingAMachine() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/job/team/api/json", exchange -> respond(exchange, """
                {"jobs":[{"name":"deploy","fullName":"team/deploy","_class":"hudson.model.FreeStyleProject","color":"blue","buildable":true}]}
                """, "application/json"));
        server.createContext("/job/team/job/deploy/config.xml", exchange -> respond(exchange,
                "<flow-definition/>", "application/xml"));
        server.start();

        UUID projectId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        EnvironmentView vmA = environment(targetId, "TF-WIN-A", "tf-win-a", "windows-vm");
        EnvironmentView vmB = environment(targetId, "TF-WIN-B", "tf-win-b", "windows-vm");
        EnvironmentView spare = environment(targetId, "TF-WIN-SPARE", "tf-win-spare", "windows-spare");
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ProjectView project = new ProjectView(projectId, "Demo", "demo", ProjectState.ACTIVE,
                TargetType.DESKTOP, "https://git.example/demo.git", "main", "JENKINS",
                baseUrl, "team", "jenkins-local",
                List.of(new TargetView(targetId, projectId, "Demo", TargetType.DESKTOP,
                        "https://git.example/demo.git", "main", List.of(vmA, vmB, spare))));
        ProjectCatalogService projects = mock(ProjectCatalogService.class);
        when(projects.requireProjectView(projectId)).thenReturn(project);
        when(projects.requireExecutionTarget(projectId)).thenReturn(project.targets().getFirst());
        when(projects.listEnvironments(true, EnvironmentPlatform.WINDOWS)).thenReturn(List.of(vmA, vmB, spare));
        when(projects.listEnvironments(true, null)).thenReturn(List.of(vmA, vmB, spare));

        DeploymentProfileRepository profiles = mock(DeploymentProfileRepository.class);
        DeploymentProfileEntity binding = mock(DeploymentProfileEntity.class);
        UUID bindingId = UUID.randomUUID();
        when(binding.getId()).thenReturn(bindingId);
        when(binding.getJobName()).thenReturn("team/deploy");
        when(binding.isEnabled()).thenReturn(true);
        when(profiles.findAllByTargetIdOrderByCreatedAtAsc(targetId)).thenReturn(List.of(binding));
        when(profiles.findByTargetIdAndJobName(targetId, "team/deploy")).thenReturn(Optional.of(binding));
        when(profiles.saveAndFlush(binding)).thenReturn(binding);

        JenkinsConnectionProperties connection = new JenkinsConnectionProperties();
        connection.setServerUrl(baseUrl);
        connection.setFolder("team");
        connection.setCredentialRef("jenkins-local");
        var service = new PipelineCatalogService(profiles, projects,
                ignored -> new CicdCredentialResolver.Credential("testforge", "token"),
                new ObjectMapper(), connection, HttpClient.newHttpClient());

        PipelineRef pipeline = service.list(projectId).getFirst();
        var selection = service.validateForPlatform(projectId, pipeline.externalId().toString(),
                EnvironmentPlatform.WINDOWS, null);

        assertThat(selection.environment().externalId()).isEqualTo("auto:WINDOWS:windows-vm");
        assertThat(selection.environment().providerKey()).isEqualTo("windows-vm");
        assertThat(selection.environment().capacity()).isEqualTo(2);
        assertThat(selection.environment().name()).contains("2 台");
        assertThat(selection.deploymentProfileId()).isEqualTo(bindingId);
    }

    @Test
    void prefersEnvironmentExplicitlyCompatibleWithSelectedPipeline() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/job/team/api/json", exchange -> respond(exchange, """
                {"jobs":[{"name":"deploy","fullName":"team/deploy","_class":"hudson.model.FreeStyleProject","color":"blue","buildable":true}]}
                """, "application/json"));
        server.createContext("/job/team/job/deploy/config.xml", exchange -> respond(exchange,
                "<flow-definition/>", "application/xml"));
        server.start();

        UUID projectId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        EnvironmentView vmA = environment(targetId, "TF-WIN-A", "tf-win-a", "windows-vm");
        EnvironmentView vmB = environment(targetId, "TF-WIN-B", "tf-win-b", "windows-vm");
        EnvironmentView selftest = new EnvironmentView(UUID.randomUUID(), targetId,
                "TestForge Selftest Host", "http://127.0.0.1:15174", "testforge-selftest",
                EnvironmentPlatform.WINDOWS, "testforge-web", 1, true, false,
                java.util.Map.of("pipelineJobNames", List.of("team/deploy")), java.util.Map.of());
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ProjectView project = new ProjectView(projectId, "TestForge", "testforge", ProjectState.ACTIVE,
                TargetType.HTTP_SERVICE, "https://git.example/testforge.git", "main", "JENKINS",
                baseUrl, "team", "jenkins-local",
                List.of(new TargetView(targetId, projectId, "TestForge", TargetType.HTTP_SERVICE,
                        "https://git.example/testforge.git", "main", List.of(vmA, vmB, selftest))));
        ProjectCatalogService projects = mock(ProjectCatalogService.class);
        when(projects.requireProjectView(projectId)).thenReturn(project);
        when(projects.requireExecutionTarget(projectId)).thenReturn(project.targets().getFirst());
        when(projects.listEnvironments(true, EnvironmentPlatform.WINDOWS))
                .thenReturn(List.of(vmA, vmB, selftest));
        when(projects.listEnvironments(true, null)).thenReturn(List.of(vmA, vmB, selftest));

        DeploymentProfileRepository profiles = mock(DeploymentProfileRepository.class);
        DeploymentProfileEntity binding = mock(DeploymentProfileEntity.class);
        UUID bindingId = UUID.randomUUID();
        when(binding.getId()).thenReturn(bindingId);
        when(binding.getJobName()).thenReturn("team/deploy");
        when(binding.isEnabled()).thenReturn(true);
        when(profiles.findAllByTargetIdOrderByCreatedAtAsc(targetId)).thenReturn(List.of(binding));
        when(profiles.findByTargetIdAndJobName(targetId, "team/deploy")).thenReturn(Optional.of(binding));
        when(profiles.saveAndFlush(binding)).thenReturn(binding);

        JenkinsConnectionProperties connection = new JenkinsConnectionProperties();
        connection.setServerUrl(baseUrl);
        connection.setFolder("team");
        connection.setCredentialRef("jenkins-local");
        var service = new PipelineCatalogService(profiles, projects,
                ignored -> new CicdCredentialResolver.Credential("testforge", "token"),
                new ObjectMapper(), connection, HttpClient.newHttpClient());

        PipelineRef pipeline = service.list(projectId).getFirst();
        var selection = service.validateForPlatform(projectId, pipeline.externalId().toString(),
                EnvironmentPlatform.WINDOWS, null);

        assertThat(selection.environment().externalId()).isEqualTo("auto:WINDOWS:testforge-web");
        assertThat(selection.environment().providerKey()).isEqualTo("testforge-selftest");
        assertThat(selection.environment().capacity()).isEqualTo(1);
        assertThat(selection.environment().name()).contains("1 台");
        assertThat(selection.deploymentProfileId()).isEqualTo(bindingId);
    }

    @Test
    void discoversUnboundMultibranchPipelineForProjectStoredInRepositorySubdirectory() throws Exception {
        Path repository = Files.createDirectories(tempDir.resolve("testforge-demo"));
        Files.createDirectory(repository.resolve(".git"));
        Path projectDirectory = Files.createDirectory(repository.resolve("testforge-platform"));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/json", exchange -> respond(exchange, """
                {"jobs":[
                  {"name":"testforge-platform-deploy","fullName":"testforge-platform-deploy","_class":"org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject","color":"blue","buildable":true},
                  {"name":"other-deploy","fullName":"other-deploy","_class":"org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject","color":"blue","buildable":true}
                ]}
                """, "application/json"));
        server.createContext("/job/testforge-platform-deploy/config.xml", exchange -> respond(exchange, """
                <org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject>
                  <sources><data><jenkins.branch.BranchSource><source><remote>git://host/testforge-demo.git</remote></source></jenkins.branch.BranchSource></data></sources>
                  <factory><scriptPath>testforge-platform/Jenkinsfile</scriptPath></factory>
                </org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject>
                """, "application/xml"));
        server.createContext("/job/other-deploy/config.xml", exchange -> respond(exchange, """
                <org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject>
                  <sources><data><jenkins.branch.BranchSource><source><remote>git://host/testforge-demo.git</remote></source></jenkins.branch.BranchSource></data></sources>
                  <factory><scriptPath>skill-sandbox/Jenkinsfile</scriptPath></factory>
                </org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject>
                """, "application/xml"));
        server.start();

        UUID projectId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ProjectView project = new ProjectView(projectId, "TestForge Platform", "testforge-platform", ProjectState.ACTIVE,
                TargetType.HTTP_SERVICE, projectDirectory.toString(), "master", "JENKINS",
                baseUrl, null, "jenkins-local",
                List.of(new TargetView(targetId, projectId, "TestForge Platform", TargetType.HTTP_SERVICE,
                        projectDirectory.toString(), "master", List.of())));
        ProjectCatalogService projects = mock(ProjectCatalogService.class);
        when(projects.requireProjectView(projectId)).thenReturn(project);
        when(projects.requireExecutionTarget(projectId)).thenReturn(project.targets().getFirst());
        DeploymentProfileRepository profiles = mock(DeploymentProfileRepository.class);
        when(profiles.findAllByTargetIdOrderByCreatedAtAsc(targetId)).thenReturn(List.of());
        JenkinsConnectionProperties connection = new JenkinsConnectionProperties();
        connection.setServerUrl(baseUrl);
        connection.setCredentialRef("jenkins-local");

        var service = new PipelineCatalogService(profiles, projects,
                ignored -> new CicdCredentialResolver.Credential("testforge", "token"),
                new ObjectMapper(), connection, HttpClient.newHttpClient());

        assertThat(service.list(projectId)).extracting(PipelineRef::jobName)
                .containsExactly("testforge-platform-deploy");
    }

    private EnvironmentView environment(UUID targetId, String name, String providerKey, String poolKey) {
        return new EnvironmentView(UUID.randomUUID(), targetId, name, "vm://" + name,
                providerKey, EnvironmentPlatform.WINDOWS, poolKey, 1, true, false,
                java.util.Map.of(), java.util.Map.of());
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String body, String contentType)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
