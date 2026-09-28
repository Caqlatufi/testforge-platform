package io.testforge.aidiagnosis.ctrl;

import io.testforge.aidiagnosis.model.DiagnosisAdvice;
import io.testforge.aidiagnosis.model.DiagnosisRequest;
import io.testforge.aidiagnosis.port.inbound.AiDiagnosisUseCase;
import io.testforge.aidiagnosis.service.AiDiagnosisException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
public class AiDiagnosisController {
    private final AiDiagnosisUseCase useCase;
    public AiDiagnosisController(AiDiagnosisUseCase useCase) { this.useCase = useCase; }

    @PostMapping("/{reportId}/diagnosis")
    public ApiResponse<DiagnosisAdvice> diagnose(@PathVariable UUID reportId, @Valid @RequestBody Request request) {
        return ApiResponse.success(useCase.diagnose(new DiagnosisRequest(reportId, request.requestKey(), request.forceRefresh())));
    }

    @GetMapping("/{reportId}/diagnosis")
    public ApiResponse<DiagnosisAdvice> latest(@PathVariable UUID reportId) {
        return ApiResponse.success(useCase.latest(reportId)
                .orElseThrow(() -> new AiDiagnosisException("DIAGNOSIS_NOT_FOUND", 404, "尚未生成 AI 诊断")));
    }

    public record Request(@NotNull UUID requestKey, boolean forceRefresh) { }
}
