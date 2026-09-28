package io.testforge.workergateway.callback.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.testforge.workergateway.callback.model.AttemptCallbackRequest;
import io.testforge.workergateway.callback.model.CallbackStatus;
import io.testforge.workergateway.callback.model.FailurePayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CallbackPayloadHasherTest {

    @Test
    void mapInsertionOrderDoesNotChangePayloadHash() {
        UUID attemptId = UUID.randomUUID();
        UUID callbackKey = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        Instant completedAt = Instant.parse("2026-09-18T06:00:00Z");

        Map<String, Object> detailsA = new LinkedHashMap<>();
        detailsA.put("expected", 200);
        detailsA.put("actual", 500);
        Map<String, Object> detailsB = new LinkedHashMap<>();
        detailsB.put("actual", 500);
        detailsB.put("expected", 200);
        Map<String, BigDecimal> metricsA = new LinkedHashMap<>();
        metricsA.put("requests", BigDecimal.ONE);
        metricsA.put("assertions", BigDecimal.TEN);
        Map<String, BigDecimal> metricsB = new LinkedHashMap<>();
        metricsB.put("assertions", BigDecimal.TEN);
        metricsB.put("requests", BigDecimal.ONE);

        AttemptCallbackRequest first = failedRequest(
                attemptId, callbackKey, leaseToken, completedAt, detailsA, metricsA
        );
        AttemptCallbackRequest second = failedRequest(
                attemptId, callbackKey, leaseToken, completedAt, detailsB, metricsB
        );
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        CallbackPayloadHasher hasher = new CallbackPayloadHasher(objectMapper);

        assertThat(hasher.hash(first)).isEqualTo(hasher.hash(second));
        assertThat(hasher.hash(first)).hasSize(64);
    }

    private AttemptCallbackRequest failedRequest(
            UUID attemptId,
            UUID callbackKey,
            UUID leaseToken,
            Instant completedAt,
            Map<String, Object> details,
            Map<String, BigDecimal> metrics
    ) {
        return new AttemptCallbackRequest(
                "1.0.0", callbackKey, attemptId, leaseToken, "worker-1",
                CallbackStatus.ASSERTION_FAILED, completedAt, 10L, "failed",
                new FailurePayload("PRODUCT_DEFECT", "bad response", null, false, details),
                List.of(), metrics
        );
    }
}
