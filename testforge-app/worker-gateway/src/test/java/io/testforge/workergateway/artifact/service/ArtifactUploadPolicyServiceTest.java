package io.testforge.workergateway.artifact.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.workergateway.artifact.model.ArtifactUploadPolicyRequest;
import io.testforge.workergateway.artifact.model.ArtifactUploadPolicyResponse;
import io.testforge.workergateway.callback.model.AttemptLeaseResponse;
import io.testforge.workergateway.callback.service.AttemptLifecycleService;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactUploadPolicyServiceTest {

    private static final UUID ATTEMPT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID LEASE_TOKEN = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-20T03:00:00Z");
    private static final String SECRET = "unit-test-secret";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsExactShortLivedPolicyWithoutExposingSecret() throws Exception {
        AttemptLifecycleService lifecycle = mock(AttemptLifecycleService.class);
        when(lifecycle.heartbeat(eq(ATTEMPT_ID), any())).thenReturn(new AttemptLeaseResponse(
                ATTEMPT_ID, "RUNNING", NOW.plusSeconds(30), BigDecimal.ONE, NOW
        ));
        ArtifactUploadPolicyService service = service(lifecycle);

        ArtifactUploadPolicyResponse response = service.create(ATTEMPT_ID, request(
                "runs/30000000-0000-4000-8000-000000000001/attempts/"
                        + ATTEMPT_ID + "/result.json"
        ));

        assertEquals("https://twintag.oss-cn-hangzhou.aliyuncs.com", response.uploadUrl());
        assertEquals(NOW.plusSeconds(300), response.expiresAt());
        assertEquals("access-id", response.fields().get("OSSAccessKeyId"));
        assertEquals(null, response.fields().get("OSSAccessKeySecret"));
        JsonNode policy = objectMapper.readTree(new String(
                Base64.getDecoder().decode(response.fields().get("policy")), StandardCharsets.UTF_8
        ));
        assertEquals(response.objectKey(), policy.path("conditions").get(0).get(2).asText());
        assertEquals(2, policy.path("conditions").get(1).get(2).asLong());
        assertEquals(sign(response.fields().get("policy")), response.fields().get("Signature"));
    }

    @Test
    void rejectsObjectKeyBelongingToAnotherAttempt() {
        ArtifactUploadPolicyService service = service(mock(AttemptLifecycleService.class));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                ATTEMPT_ID,
                request("runs/30000000-0000-4000-8000-000000000001/attempts/"
                        + UUID.randomUUID() + "/result.json")
        ));
    }

    private ArtifactUploadPolicyService service(AttemptLifecycleService lifecycle) {
        return new ArtifactUploadPolicyService(
                lifecycle,
                objectMapper,
                "access-id",
                SECRET,
                "https://oss-cn-hangzhou.aliyuncs.com",
                "twintag",
                "universal-test-platform",
                Duration.ofMinutes(5),
                1024,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private ArtifactUploadPolicyRequest request(String objectKey) {
        return new ArtifactUploadPolicyRequest(
                "worker-1", LEASE_TOKEN, objectKey, "application/json", 2,
                "a".repeat(64)
        );
    }

    private String sign(String encodedPolicy) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(
                mac.doFinal(encodedPolicy.getBytes(StandardCharsets.UTF_8))
        );
    }
}
