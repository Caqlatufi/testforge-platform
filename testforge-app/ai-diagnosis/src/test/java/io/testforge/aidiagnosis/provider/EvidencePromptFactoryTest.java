package io.testforge.aidiagnosis.provider;

import io.testforge.aidiagnosis.config.AiDiagnosisProperties;
import io.testforge.report.model.EvidenceReference;
import io.testforge.report.model.EvidenceType;
import io.testforge.report.model.ReportEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidencePromptFactoryTest {
    @Test
    void buildsStableBoundedPromptAndRedactsCommonSecrets() {
        AiDiagnosisProperties properties = new AiDiagnosisProperties();
        properties.setMaxEvidenceChars(80);
        EvidencePromptFactory factory = new EvidencePromptFactory(properties);
        ReportEvidence evidence = new ReportEvidence(UUID.randomUUID(), List.of(
                new EvidenceReference("log:2", EvidenceType.LOG,
                        "token=should-not-leak Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature "
                                + "https://user:pass@example.test sk-proj-abcdefghijklmnopqrstuvwxyz"),
                new EvidenceReference("assert:1", EvidenceType.ASSERTION, "expected 200 but got 500")
        ));

        String prompt = factory.create(evidence);

        assertTrue(prompt.indexOf("assert:1") < prompt.indexOf("log:2"));
        assertTrue(prompt.contains("ALLOWED_EVIDENCE_IDS: [\"assert:1\", \"log:2\"]"));
        assertTrue(prompt.contains("copied character-for-character"));
        assertTrue(prompt.contains("token=***"));
        assertFalse(prompt.contains("should-not-leak"));
        assertFalse(prompt.contains("eyJhbGci"));
        assertFalse(prompt.contains("user:pass"));
        assertFalse(prompt.contains("sk-proj-"));
        assertEquals(factory.hash(evidence), factory.hash(evidence));
    }
}
