package io.testforge.aidiagnosis.ctrl;

import io.testforge.aidiagnosis.port.outbound.AiDiagnosisProviderPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
public class AiDiagnosisStatusController {
    private final AiDiagnosisProviderPort provider;

    public AiDiagnosisStatusController(AiDiagnosisProviderPort provider) {
        this.provider = provider;
    }

    @GetMapping("/{runId}/diagnosis/status")
    public ApiResponse<Map<String, Object>> status(@PathVariable UUID runId) {
        var status = provider.status();
        return ApiResponse.success(Map.of(
                "runId", runId,
                "available", status.available(),
                "status", status.status(),
                "message", status.message(),
                "provider", status.provider(),
                "model", status.model(),
                "reasoningEffort", status.reasoningEffort()
        ));
    }
}
