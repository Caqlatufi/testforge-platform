package io.testforge.aidiagnosis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("testforge.ai")
public class AiDiagnosisProperties {
    private boolean enabled;
    private String provider = "codex-cli";
    private String codexCommand = "codex";
    private String codexHome;
    private String model = "gpt-5.6-luna";
    private String reasoningEffort = "high";
    private Duration timeout = Duration.ofSeconds(90);
    private Duration readinessTimeout = Duration.ofSeconds(3);
    private int maxConcurrency = 1;
    private int maxEvidence = 100;
    private int maxEvidenceChars = 2_000;
    private int maxPromptChars = 100_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getCodexCommand() { return codexCommand; }
    public void setCodexCommand(String codexCommand) { this.codexCommand = codexCommand; }
    public String getCodexHome() { return codexHome; }
    public void setCodexHome(String codexHome) { this.codexHome = codexHome; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public Duration getReadinessTimeout() { return readinessTimeout; }
    public void setReadinessTimeout(Duration readinessTimeout) { this.readinessTimeout = readinessTimeout; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public int getMaxEvidence() { return maxEvidence; }
    public void setMaxEvidence(int maxEvidence) { this.maxEvidence = maxEvidence; }
    public int getMaxEvidenceChars() { return maxEvidenceChars; }
    public void setMaxEvidenceChars(int maxEvidenceChars) { this.maxEvidenceChars = maxEvidenceChars; }
    public int getMaxPromptChars() { return maxPromptChars; }
    public void setMaxPromptChars(int maxPromptChars) { this.maxPromptChars = maxPromptChars; }
}
