package io.testforge.aidiagnosis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.aidiagnosis.config.AiDiagnosisProperties;
import io.testforge.aidiagnosis.model.ProviderDiagnosis;
import io.testforge.aidiagnosis.model.ProviderStatus;
import io.testforge.aidiagnosis.port.outbound.AiDiagnosisProviderPort;
import io.testforge.aidiagnosis.service.AiDiagnosisException;
import io.testforge.report.model.ReportEvidence;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CodexCliDiagnosisProvider implements AiDiagnosisProviderPort {
    private static final int MAX_ERROR_CHARS = 4_000;
    private static final Set<String> ALLOWED_ENVIRONMENT = Set.of(
            "PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP",
            "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA",
            "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE",
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY"
    );
    private final AiDiagnosisProperties properties;
    private final EvidencePromptFactory promptFactory;
    private final ObjectMapper objectMapper;
    private final Semaphore concurrency;

    public CodexCliDiagnosisProvider(AiDiagnosisProperties properties, EvidencePromptFactory promptFactory,
                                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.promptFactory = promptFactory;
        this.objectMapper = objectMapper;
        this.concurrency = new Semaphore(Math.max(1, properties.getMaxConcurrency()));
    }

    @Override
    public ProviderStatus status() {
        if (!properties.isEnabled()) return unavailable("AI 诊断未启用");
        if (properties.getCodexHome() == null || properties.getCodexHome().isBlank()) {
            return unavailable("未配置独立的 TESTFORGE_AI_CODEX_HOME");
        }
        try {
            Process process = processBuilder(List.of(properties.getCodexCommand(), "login", "status"))
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(properties.getReadinessTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
                return unavailable("Codex CLI 登录状态检查超时");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0 || !output.toLowerCase().contains("logged in")) {
                return unavailable("Codex CLI 未登录");
            }
            return new ProviderStatus(true, "AVAILABLE", "Codex CLI 已登录",
                    properties.getProvider(), properties.getModel(), properties.getReasoningEffort());
        } catch (IOException exception) {
            return unavailable("无法启动 Codex CLI：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unavailable("Codex CLI 状态检查被中断");
        }
    }

    @Override
    public ProviderDiagnosis diagnose(ReportEvidence evidence) {
        ProviderStatus readiness = status();
        if (!readiness.available()) throw new AiDiagnosisException("AI_PROVIDER_UNAVAILABLE", 503, readiness.message());
        if (!concurrency.tryAcquire()) throw new AiDiagnosisException("AI_PROVIDER_BUSY", 429, "AI Provider 正在处理其他诊断");
        Path directory = null;
        try {
            directory = Files.createTempDirectory("testforge-ai-");
            Path schema = directory.resolve("diagnosis.schema.json");
            Path output = directory.resolve("diagnosis.json");
            Path stdout = directory.resolve("stdout.log");
            Path stderr = directory.resolve("stderr.log");
            Files.writeString(schema, loadSchema(), StandardCharsets.UTF_8);
            List<String> command = List.of(
                    properties.getCodexCommand(), "exec", "--ephemeral", "--ignore-user-config",
                    "--model", properties.getModel(),
                    "-c", "model_reasoning_effort=\"" + properties.getReasoningEffort() + "\"",
                    "--sandbox", "read-only", "--skip-git-repo-check",
                    "--output-schema", schema.toString(),
                    "--output-last-message", output.toString(),
                    "-C", directory.toString(), "-"
            );
            Process process = processBuilder(command)
                    .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(promptFactory.create(evidence).getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                throw new AiDiagnosisException("AI_PROVIDER_TIMEOUT", 504,
                        "AI Provider 超过 " + properties.getTimeout().toSeconds() + " 秒未完成");
            }
            if (process.exitValue() != 0) {
                throw new AiDiagnosisException("AI_PROVIDER_UNAVAILABLE", 503,
                        "Codex CLI 调用失败：" + limitedRead(stderr));
            }
            if (!Files.exists(output)) throw new AiDiagnosisException("AI_OUTPUT_INVALID", 422, "Codex CLI 未产生结构化结果");
            ProviderDiagnosis result = objectMapper.readValue(output.toFile(), ProviderDiagnosis.class);
            validateShape(result);
            return result;
        } catch (AiDiagnosisException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AiDiagnosisException("AI_PROVIDER_UNAVAILABLE", 503, "Codex CLI 调用失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiDiagnosisException("AI_PROVIDER_UNAVAILABLE", 503, "Codex CLI 调用被中断", exception);
        } finally {
            concurrency.release();
            deleteTree(directory);
        }
    }

    private void validateShape(ProviderDiagnosis value) {
        if (value == null || value.category() == null || value.evidence() == null
                || value.suggestions() == null || value.missingEvidence() == null
                || value.confidence() < 0 || value.confidence() > 1) {
            throw new AiDiagnosisException("AI_OUTPUT_INVALID", 422, "AI 输出缺少必填字段或置信度越界");
        }
    }

    private ProviderStatus unavailable(String message) {
        return new ProviderStatus(false, "UNAVAILABLE", message,
                properties.getProvider(), properties.getModel(), properties.getReasoningEffort());
    }

    private String limitedRead(Path path) throws IOException {
        if (!Files.exists(path)) return "无错误输出";
        String value = Files.readString(path, StandardCharsets.UTF_8);
        value = promptFactory.redact(value);
        return value.substring(0, Math.min(MAX_ERROR_CHARS, value.length()));
    }

    ProcessBuilder processBuilder(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        Map<String, String> inherited = Map.copyOf(environment);
        environment.clear();
        inherited.forEach((name, value) -> {
            if (ALLOWED_ENVIRONMENT.contains(name.toUpperCase(Locale.ROOT))) {
                environment.put(name, value);
            }
        });
        environment.put("CODEX_HOME", Path.of(properties.getCodexHome()).toAbsolutePath().normalize().toString());
        return builder;
    }

    private void terminate(Process process) {
        process.descendants().forEach(handle -> handle.destroyForcibly());
        process.destroyForcibly();
    }

    private void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private String loadSchema() throws IOException {
        try (var input = CodexCliDiagnosisProvider.class.getResourceAsStream("/io/testforge/aidiagnosis/diagnosis-output.schema.json")) {
            if (input == null) throw new IOException("诊断输出 Schema 资源不存在");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
