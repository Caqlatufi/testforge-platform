package io.testforge.aidiagnosis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.aidiagnosis.config.AiDiagnosisProperties;
import io.testforge.aidiagnosis.model.DiagnosisAdvice;
import io.testforge.aidiagnosis.model.DiagnosisRequest;
import io.testforge.aidiagnosis.model.EvidenceCitation;
import io.testforge.aidiagnosis.model.ProviderDiagnosis;
import io.testforge.aidiagnosis.port.inbound.AiDiagnosisUseCase;
import io.testforge.aidiagnosis.port.outbound.AiDiagnosisProviderPort;
import io.testforge.aidiagnosis.provider.EvidencePromptFactory;
import io.testforge.aidiagnosis.repo.AiDiagnosisRecordEntity;
import io.testforge.aidiagnosis.repo.AiDiagnosisRecordRepository;
import io.testforge.report.model.ReportEvidence;
import io.testforge.report.port.inbound.ReportEvidenceQueryPort;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class AiDiagnosisService implements AiDiagnosisUseCase {
    private static final Set<String> CATEGORIES = Set.of(
            "PRODUCT_DEFECT", "SCRIPT_ERROR", "ENVIRONMENT", "WORKER_LOST", "FLAKY", "UNKNOWN");
    private static final TypeReference<List<EvidenceCitation>> CITATIONS = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final ReportEvidenceQueryPort evidenceQuery;
    private final AiDiagnosisProviderPort provider;
    private final AiDiagnosisRecordRepository repository;
    private final EvidencePromptFactory promptFactory;
    private final AiDiagnosisProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AiDiagnosisService(ReportEvidenceQueryPort evidenceQuery, AiDiagnosisProviderPort provider,
                              AiDiagnosisRecordRepository repository, EvidencePromptFactory promptFactory,
                              AiDiagnosisProperties properties, ObjectMapper objectMapper) {
        this(evidenceQuery, provider, repository, promptFactory, properties, objectMapper, Clock.systemUTC());
    }

    AiDiagnosisService(ReportEvidenceQueryPort evidenceQuery, AiDiagnosisProviderPort provider,
                       AiDiagnosisRecordRepository repository, EvidencePromptFactory promptFactory,
                       AiDiagnosisProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.evidenceQuery = evidenceQuery; this.provider = provider; this.repository = repository;
        this.promptFactory = promptFactory; this.properties = properties; this.objectMapper = objectMapper; this.clock = clock;
    }

    @Override
    public DiagnosisAdvice diagnose(DiagnosisRequest request) {
        Optional<AiDiagnosisRecordEntity> idempotent = repository.findByReportIdAndRequestKey(request.reportId(), request.requestKey());
        if (idempotent.isPresent()) {
            if ("SUCCEEDED".equals(idempotent.get().getStatus())) return toAdvice(idempotent.get(), true);
            if ("RUNNING".equals(idempotent.get().getStatus()))
                throw new AiDiagnosisException("DIAGNOSIS_IN_PROGRESS", 409, "该诊断请求正在执行");
            throw new AiDiagnosisException("DIAGNOSIS_REQUEST_FAILED", 409,
                    "该 requestKey 对应的诊断已经失败，请使用新的 requestKey 重试");
        }

        ReportEvidence evidence = evidenceQuery.findByReportId(request.reportId())
                .orElseThrow(() -> new AiDiagnosisException("REPORT_NOT_FOUND", 404, "报告不存在或尚无结果"));
        String hash = promptFactory.hash(evidence);
        if (!request.forceRefresh()) {
            Optional<AiDiagnosisRecordEntity> cached = repository
                    .findFirstByReportIdAndEvidenceHashAndStatusOrderByCompletedAtDesc(request.reportId(), hash, "SUCCEEDED");
            if (cached.isPresent()) return cloneForRequest(cached.get(), request.requestKey());
        }

        Instant started = clock.instant();
        AiDiagnosisRecordEntity record = AiDiagnosisRecordEntity.running(request.reportId(), request.requestKey(), hash,
                properties.getProvider(), properties.getModel(), properties.getReasoningEffort(), started);
        repository.saveAndFlush(record);
        try {
            ProviderDiagnosis result = evidence.evidence().isEmpty()
                    ? new ProviderDiagnosis("UNKNOWN", 0, List.of(), List.of("补充报告证据后重新诊断"), List.of("失败断言或日志"))
                    : provider.diagnose(evidence);
            validate(result, evidence);
            record.succeed(result.category(), result.confidence(), write(result.evidence()), write(result.suggestions()),
                    write(result.missingEvidence()), clock.instant());
            return toAdvice(repository.save(record), false);
        } catch (RuntimeException exception) {
            AiDiagnosisException mapped = exception instanceof AiDiagnosisException value ? value
                    : new AiDiagnosisException("AI_PROVIDER_UNAVAILABLE", 503, "AI Provider 调用失败", exception);
            record.fail(mapped.code(), mapped.getMessage(), clock.instant());
            repository.save(record);
            throw mapped;
        }
    }

    @Override
    public Optional<DiagnosisAdvice> latest(UUID reportId) {
        return repository.findFirstByReportIdAndStatusOrderByCompletedAtDesc(reportId, "SUCCEEDED")
                .map(value -> toAdvice(value, true));
    }

    private DiagnosisAdvice cloneForRequest(AiDiagnosisRecordEntity source, UUID requestKey) {
        AiDiagnosisRecordEntity clone = AiDiagnosisRecordEntity.running(source.getReportId(), requestKey,
                source.getEvidenceHash(), source.getProvider(), source.getModel(), source.getReasoningEffort(), clock.instant());
        clone.succeed(source.getCategory(), source.getConfidence(), source.getEvidenceJson(), source.getSuggestionsJson(),
                source.getMissingEvidenceJson(), clock.instant());
        return toAdvice(repository.save(clone), true);
    }

    private void validate(ProviderDiagnosis result, ReportEvidence evidence) {
        if (!CATEGORIES.contains(result.category()))
            throw new AiDiagnosisException("AI_OUTPUT_INVALID", 422, "AI 输出包含未知分类");
        if (result.confidence() < 0 || result.confidence() > 1)
            throw new AiDiagnosisException("AI_OUTPUT_INVALID", 422, "AI 输出置信度越界");
        Set<String> allowed = evidence.evidence().stream().map(item -> item.evidenceId()).collect(java.util.stream.Collectors.toSet());
        boolean unknown = result.evidence().stream().anyMatch(item -> !allowed.contains(item.evidenceId()));
        if (unknown) throw new AiDiagnosisException("AI_OUTPUT_INVALID", 422, "AI 输出引用了不存在的证据");
    }

    private DiagnosisAdvice toAdvice(AiDiagnosisRecordEntity value, boolean reused) {
        return new DiagnosisAdvice(value.getId(), value.getReportId(), value.getCategory(), value.getConfidence(),
                read(value.getEvidenceJson(), CITATIONS), read(value.getSuggestionsJson(), STRINGS),
                read(value.getMissingEvidenceJson(), STRINGS), value.getProvider(), value.getModel(),
                value.getReasoningEffort(), reused, value.getCompletedAt());
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("诊断结果无法序列化", exception); }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("诊断结果无法读取", exception); }
    }
}
