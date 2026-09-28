package io.testforge.cicdgateway.provider;

import io.testforge.cicdgateway.deployment.entity.DeploymentProfileEntity;
import io.testforge.cicdgateway.deployment.model.DeploymentProviderType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JenkinsDeploymentProvider implements DeploymentProvider {
    private final HttpClient client;
    private final CicdCredentialResolver credentials;

    @Autowired
    public JenkinsDeploymentProvider(CicdCredentialResolver credentials) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), credentials);
    }

    JenkinsDeploymentProvider(HttpClient client, CicdCredentialResolver credentials) {
        this.client = client; this.credentials = credentials;
    }

    @Override public DeploymentProviderType type() { return DeploymentProviderType.JENKINS; }

    @Override
    public DeploymentTriggerResult trigger(DeploymentProfileEntity profile, DeploymentTriggerRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("DEPLOYMENT_ID", request.deploymentId().toString());
        fields.put("TARGET_REPOSITORY", request.repositoryUrl());
        fields.put("COMMIT_SHA", request.resolvedCommit());
        if (request.testEnvironmentId() != null) {
            fields.put("TEST_ENVIRONMENT_ID", request.testEnvironmentId().toString());
        }
        if (request.environmentExternalId() != null && !request.environmentExternalId().isBlank()) {
            fields.put("DEPLOY_ENVIRONMENT", request.environmentExternalId());
        }
        fields.put("INITIALIZE_ENVIRONMENT", Boolean.toString(request.initializeEnvironment()));
        fields.put("CALLBACK_URL", request.callbackUrl());
        String body = fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        String base = profile.getServerUrl().replaceAll("/+$", "");
        String jobPath = java.util.Arrays.stream(profile.getJobName().split("/"))
                .map(segment -> "job/" + encodePath(segment)).reduce((left, right) -> left + "/" + right).orElseThrow();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/" + jobPath + "/buildWithParameters"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        CicdCredentialResolver.Credential credential = credentials.resolve(profile.getCredentialRef());
        if (credential != null) {
            String raw = credential.username() + ":" + credential.token();
            builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        }
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 400) {
                throw new IllegalStateException("Jenkins 触发失败，HTTP " + response.statusCode());
            }
            String location = response.headers().firstValue("Location")
                    .orElse("jenkins:" + request.deploymentId());
            return new DeploymentTriggerResult(location);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Jenkins 触发被中断", error);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Jenkins 连接失败", error);
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String encodePath(String value) { return encode(value).replace("+", "%20"); }
}
