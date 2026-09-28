package io.testforge.cicdgateway.provider;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentCicdCredentialResolver implements CicdCredentialResolver {
    private final Environment environment;

    public EnvironmentCicdCredentialResolver(Environment environment) { this.environment = environment; }

    @Override
    public Credential resolve(String reference) {
        if (reference == null || reference.isBlank()) return null;
        String prefix = "testforge.cicd.credentials." + reference.trim() + ".";
        String username = environment.getProperty(prefix + "username");
        String token = environment.getProperty(prefix + "token");
        if (username == null || token == null) throw new IllegalStateException("CI/CD 凭据引用未配置: " + reference);
        return new Credential(username, token);
    }
}
