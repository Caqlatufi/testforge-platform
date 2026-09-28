package io.testforge.cicdgateway.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.testforge.cicdgateway.deployment.entity.DeploymentProfileEntity;
import io.testforge.cicdgateway.deployment.model.CreateDeploymentProfileCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JenkinsDeploymentProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void triggersFolderJobWithImmutableCommitAndCallback() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/job/team/job/deploy/buildWithParameters", exchange -> {
            capture(exchange, path, body, authorization);
            exchange.getResponseHeaders().add("Location", "http://jenkins/queue/item/42/");
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();

        var credential = new CicdCredentialResolver.Credential("testforge", "local-token");
        var provider = new JenkinsDeploymentProvider(HttpClient.newHttpClient(), ignored -> credential);
        UUID deploymentId = UUID.randomUUID();
        UUID testEnvironmentId = UUID.randomUUID();
        String commit = "0123456789abcdef0123456789abcdef01234567";
        var result = provider.trigger(
                profile("http://127.0.0.1:" + server.getAddress().getPort(), "team/deploy", "jenkins-local"),
                new DeploymentTriggerRequest(deploymentId, "https://example.test/repository.git", commit,
                        "http://host.docker.internal:8081/api/v1/deployments/callback", testEnvironmentId,
                        "staging", true)
        );

        assertThat(path.get()).isEqualTo("/job/team/job/deploy/buildWithParameters");
        assertThat(form(body.get())).containsEntry("DEPLOYMENT_ID", deploymentId.toString())
                .containsEntry("TARGET_REPOSITORY", "https://example.test/repository.git")
                .containsEntry("COMMIT_SHA", commit)
                .containsEntry("TEST_ENVIRONMENT_ID", testEnvironmentId.toString())
                .containsEntry("DEPLOY_ENVIRONMENT", "staging")
                .containsEntry("INITIALIZE_ENVIRONMENT", "true")
                .containsEntry("CALLBACK_URL", "http://host.docker.internal:8081/api/v1/deployments/callback");
        assertThat(authorization.get()).startsWith("Basic ");
        assertThat(result.providerRunId()).isEqualTo("http://jenkins/queue/item/42/");
    }

    @Test
    void rejectsNonSuccessfulJenkinsResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/job/deploy/buildWithParameters", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        var provider = new JenkinsDeploymentProvider(HttpClient.newHttpClient(), ignored -> null);

        assertThatThrownBy(() -> provider.trigger(
                profile("http://127.0.0.1:" + server.getAddress().getPort(), "deploy", null),
                new DeploymentTriggerRequest(UUID.randomUUID(), "repo", "a".repeat(40), "http://callback")
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 503");
    }

    private DeploymentProfileEntity profile(String serverUrl, String jobName, String credentialRef) {
        return new DeploymentProfileEntity(UUID.randomUUID(), UUID.randomUUID(),
                new CreateDeploymentProfileCommand("local", serverUrl, jobName, credentialRef,
                        "sha256:" + "0".repeat(64), 2, 600), Instant.now());
    }

    private void capture(HttpExchange exchange, AtomicReference<String> path, AtomicReference<String> body,
                         AtomicReference<String> authorization) throws IOException {
        path.set(exchange.getRequestURI().getPath());
        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private Map<String, String> form(String encoded) {
        return Arrays.stream(encoded.split("&")).map(field -> field.split("=", 2))
                .collect(Collectors.toMap(parts -> decode(parts[0]), parts -> decode(parts[1])));
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
