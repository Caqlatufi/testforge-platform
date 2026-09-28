package io.testforge.aidiagnosis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.aidiagnosis.config.AiDiagnosisProperties;
import io.testforge.aidiagnosis.model.DiagnosisRequest;
import io.testforge.aidiagnosis.model.EvidenceCitation;
import io.testforge.aidiagnosis.model.ProviderDiagnosis;
import io.testforge.aidiagnosis.port.outbound.AiDiagnosisProviderPort;
import io.testforge.aidiagnosis.provider.EvidencePromptFactory;
import io.testforge.aidiagnosis.repo.AiDiagnosisRecordEntity;
import io.testforge.aidiagnosis.repo.AiDiagnosisRecordRepository;
import io.testforge.report.model.EvidenceReference;
import io.testforge.report.model.EvidenceType;
import io.testforge.report.model.ReportEvidence;
import io.testforge.report.port.inbound.ReportEvidenceQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiDiagnosisServiceTest {
    private final ReportEvidenceQueryPort evidenceQuery = mock(ReportEvidenceQueryPort.class);
    private final AiDiagnosisProviderPort provider = mock(AiDiagnosisProviderPort.class);
    private final AiDiagnosisRecordRepository repository = mock(AiDiagnosisRecordRepository.class);
    private AiDiagnosisService service;

    @BeforeEach
    void setUp() {
        AiDiagnosisProperties properties = new AiDiagnosisProperties();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        service = new AiDiagnosisService(evidenceQuery, provider, repository,
                new EvidencePromptFactory(properties), properties, mapper);
        when(repository.findByReportIdAndRequestKey(any(), any())).thenReturn(Optional.empty());
        when(repository.findFirstByReportIdAndEvidenceHashAndStatusOrderByCompletedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void persistsValidatedEvidenceBoundAdvice() {
        UUID reportId = UUID.randomUUID();
        when(evidenceQuery.findByReportId(reportId)).thenReturn(Optional.of(evidence(reportId)));
        when(provider.diagnose(any())).thenReturn(new ProviderDiagnosis("SCRIPT_ERROR", .8,
                List.of(new EvidenceCitation("result:1", "定位器超时")), List.of("检查定位器"), List.of()));

        var result = service.diagnose(new DiagnosisRequest(reportId, UUID.randomUUID(), false));

        assertEquals("SCRIPT_ERROR", result.category());
        assertEquals("result:1", result.evidence().getFirst().evidenceId());
        assertEquals("gpt-5.6-luna", result.model());
    }

    @Test
    void rejectsProviderCitationThatWasNotInTheReport() {
        UUID reportId = UUID.randomUUID();
        when(evidenceQuery.findByReportId(reportId)).thenReturn(Optional.of(evidence(reportId)));
        when(provider.diagnose(any())).thenReturn(new ProviderDiagnosis("PRODUCT_DEFECT", .9,
                List.of(new EvidenceCitation("invented", "not supplied")), List.of(), List.of()));

        AiDiagnosisException error = assertThrows(AiDiagnosisException.class,
                () -> service.diagnose(new DiagnosisRequest(reportId, UUID.randomUUID(), false)));

        assertEquals("AI_OUTPUT_INVALID", error.code());
    }

    private ReportEvidence evidence(UUID reportId) {
        return new ReportEvidence(reportId,
                List.of(new EvidenceReference("result:1", EvidenceType.ASSERTION, "locator timed out")));
    }
}
