package io.testforge.aidiagnosis.provider;

import io.testforge.aidiagnosis.config.AiDiagnosisProperties;
import io.testforge.report.model.EvidenceReference;
import io.testforge.report.model.ReportEvidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class EvidencePromptFactory {
    private static final Pattern ASSIGNMENT_SECRET = Pattern.compile(
            "(?i)(access[-_ ]?key(?:[-_ ]?(?:id|secret))?|api[-_ ]?key|client[-_ ]?secret|secret|token|password|authorization|cookie)"
                    + "\\s*[:=]\\s*[\\\"']?[^\\s,;\\\"']+");
    private static final Pattern BEARER_SECRET = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/=-]+");
    private static final Pattern KNOWN_TOKEN = Pattern.compile(
            "(?i)\\b(?:sk-(?:proj-)?[a-z0-9_-]{12,}|ghp_[a-z0-9]{20,}|github_pat_[a-z0-9_]{20,}|"
                    + "AKIA[A-Z0-9]{16}|LTAI[A-Za-z0-9]{12,})\\b");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
    private static final Pattern URL_CREDENTIAL = Pattern.compile(
            "(?i)(https?://)[^\\s/:@]+:[^\\s/@]+@");
    private final AiDiagnosisProperties properties;

    public EvidencePromptFactory(AiDiagnosisProperties properties) { this.properties = properties; }

    public String create(ReportEvidence report) {
        List<EvidenceReference> sorted = report.evidence().stream()
                .sorted(Comparator.comparing((EvidenceReference item) -> item.type().name())
                        .thenComparing(EvidenceReference::evidenceId))
                .limit(properties.getMaxEvidence()).toList();
        String allowedEvidenceIds = sorted.stream()
                .map(EvidenceReference::evidenceId)
                .map(id -> "\"" + escapeJson(id) + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
        StringBuilder value = new StringBuilder("""
                You diagnose automated test failures using only the supplied evidence.
                Your output must match the provided JSON schema. Never invent evidence IDs.
                Every evidence[].evidenceId must be copied character-for-character from ALLOWED_EVIDENCE_IDS below.
                Artifact paths and report, run, task, attempt, or case IDs are not evidence IDs unless they appear in that list.
                If no listed evidence supports a claim, return an empty evidence array and explain the gap in missingEvidence.
                AI advice is non-authoritative and must not change the deterministic test result.
                If evidence is insufficient, use category UNKNOWN and list missing evidence.
                Allowed categories: PRODUCT_DEFECT, SCRIPT_ERROR, ENVIRONMENT, WORKER_LOST, FLAKY, UNKNOWN.
                Report ID: """).append(report.reportId())
                .append("\nALLOWED_EVIDENCE_IDS: ").append(allowedEvidenceIds)
                .append("\nEvidence:\n");
        for (EvidenceReference item : sorted) {
            String summary = redact(item.summary());
            summary = summary.substring(0, Math.min(properties.getMaxEvidenceChars(), summary.length()));
            value.append("- [").append(item.evidenceId()).append("] type=").append(item.type())
                    .append(" summary=").append(summary.replace('\n', ' ')).append('\n');
            if (value.length() >= properties.getMaxPromptChars()) break;
        }
        return value.substring(0, Math.min(value.length(), properties.getMaxPromptChars()));
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public String hash(ReportEvidence report) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(create(report).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    String redact(String value) {
        String redacted = ASSIGNMENT_SECRET.matcher(value).replaceAll("$1=***");
        redacted = BEARER_SECRET.matcher(redacted).replaceAll("Bearer ***");
        redacted = KNOWN_TOKEN.matcher(redacted).replaceAll("***");
        redacted = JWT.matcher(redacted).replaceAll("***");
        return URL_CREDENTIAL.matcher(redacted).replaceAll("$1***:***@");
    }
}
