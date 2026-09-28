package io.testforge.workergateway.artifact.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.workergateway.artifact.model.ArtifactUploadPolicyRequest;
import io.testforge.workergateway.artifact.model.ArtifactUploadPolicyResponse;
import io.testforge.workergateway.callback.model.AttemptHeartbeatRequest;
import io.testforge.workergateway.callback.service.AttemptLifecycleService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class ArtifactUploadPolicyService {

    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._/-]+$");

    private final AttemptLifecycleService lifecycleService;
    private final ObjectMapper objectMapper;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String bucket;
    private final String prefix;
    private final String uploadUrl;
    private final Duration policyDuration;
    private final long maxObjectSize;
    private final Clock clock;

    public ArtifactUploadPolicyService(
            AttemptLifecycleService lifecycleService,
            ObjectMapper objectMapper,
            String accessKeyId,
            String accessKeySecret,
            String endpoint,
            String bucket,
            String prefix,
            Duration policyDuration,
            long maxObjectSize,
            Clock clock
    ) {
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.accessKeyId = requireText(accessKeyId, "accessKeyId");
        this.accessKeySecret = requireText(accessKeySecret, "accessKeySecret");
        this.bucket = requireText(bucket, "bucket");
        this.prefix = trimSlashes(prefix == null ? "" : prefix);
        this.uploadUrl = bucketUploadUrl(requireText(endpoint, "endpoint"), bucket);
        this.policyDuration = Objects.requireNonNull(policyDuration, "policyDuration 不能为空");
        if (policyDuration.isZero() || policyDuration.isNegative()) {
            throw new IllegalArgumentException("policyDuration 必须为正数");
        }
        if (maxObjectSize <= 0) {
            throw new IllegalArgumentException("maxObjectSize 必须为正数");
        }
        this.maxObjectSize = maxObjectSize;
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    public ArtifactUploadPolicyResponse create(UUID attemptId, ArtifactUploadPolicyRequest request) {
        if (request.sizeBytes() > maxObjectSize) {
            throw new IllegalArgumentException("附件大小超过限制");
        }
        String relativeKey = normalizeAndValidateKey(attemptId, request.objectKey());
        Instant now = clock.instant();
        lifecycleService.heartbeat(attemptId, new AttemptHeartbeatRequest(
                request.workerId(), request.leaseToken(), BigDecimal.ONE, now
        ));
        Instant expiresAt = now.plus(policyDuration);

        String storedKey = prefix.isEmpty() ? relativeKey : prefix + "/" + relativeKey;
        String postPolicy = serializePolicy(expiresAt, storedKey, request);
        String encodedPolicy = Base64.getEncoder().encodeToString(
                postPolicy.getBytes(StandardCharsets.UTF_8)
        );
        String signature = sign(encodedPolicy);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", storedKey);
        fields.put("OSSAccessKeyId", accessKeyId);
        fields.put("policy", encodedPolicy);
        fields.put("Signature", signature);
        fields.put("success_action_status", "200");
        fields.put("Content-Type", request.mediaType());
        fields.put("x-oss-meta-sha256", request.sha256());
        return new ArtifactUploadPolicyResponse(uploadUrl, storedKey, expiresAt, Map.copyOf(fields));
    }

    private String serializePolicy(
            Instant expiresAt,
            String storedKey,
            ArtifactUploadPolicyRequest request
    ) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("expiration", expiresAt.toString());
        policy.put("conditions", List.of(
                List.of("eq", "$key", storedKey),
                List.of("content-length-range", request.sizeBytes(), request.sizeBytes()),
                List.of("eq", "$Content-Type", request.mediaType()),
                List.of("eq", "$x-oss-meta-sha256", request.sha256())
        ));
        try {
            return objectMapper.writeValueAsString(policy);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成 OSS 上传策略", exception);
        }
    }

    private String sign(String encodedPolicy) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(accessKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(encodedPolicy.getBytes(StandardCharsets.UTF_8))
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法签名 OSS 上传策略", exception);
        }
    }

    private String normalizeAndValidateKey(UUID attemptId, String requestedKey) {
        String key = trimSlashes(requestedKey.replace('\\', '/'));
        String requiredSegment = "/attempts/" + attemptId + "/";
        if (!key.startsWith("runs/")
                || !key.contains(requiredSegment)
                || key.contains("..")
                || !SAFE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("附件 objectKey 与 Attempt 不匹配");
        }
        return key;
    }

    private static String bucketUploadUrl(String endpoint, String bucket) {
        String normalized = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        int schemeIndex = normalized.indexOf("://");
        if (schemeIndex < 0) {
            return "https://" + bucket + "." + normalized;
        }
        return normalized.substring(0, schemeIndex + 3) + bucket + "." + normalized.substring(schemeIndex + 3);
    }

    private static String trimSlashes(String value) {
        return value.replaceAll("^/+|/+$", "");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
