package io.testforge.aidiagnosis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.aidiagnosis.config.AiDiagnosisProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexCliDiagnosisProviderTest {
    @Test
    void startsCodexWithDedicatedHomeAndEnvironmentAllowlist() {
        AiDiagnosisProperties properties = new AiDiagnosisProperties();
        properties.setCodexHome("build/test-codex-home");
        EvidencePromptFactory promptFactory = new EvidencePromptFactory(properties);
        CodexCliDiagnosisProvider provider = new CodexCliDiagnosisProvider(
                properties, promptFactory, new ObjectMapper());

        ProcessBuilder builder = provider.processBuilder(List.of("codex", "login", "status"));
        Set<String> allowed = Set.of(
                "PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP",
                "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "SSL_CERT_FILE", "SSL_CERT_DIR",
                "REQUESTS_CA_BUNDLE", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "CODEX_HOME");

        assertEquals(Path.of("build/test-codex-home").toAbsolutePath().normalize().toString(),
                builder.environment().get("CODEX_HOME"));
        assertTrue(builder.environment().keySet().stream()
                .map(name -> name.toUpperCase(Locale.ROOT)).allMatch(allowed::contains));
        assertFalse(builder.environment().containsKey("MYSQL_PASSWORD"));
        assertFalse(builder.environment().containsKey("TESTFORGE_OSS_ACCESS_KEY_SECRET"));
    }
}
