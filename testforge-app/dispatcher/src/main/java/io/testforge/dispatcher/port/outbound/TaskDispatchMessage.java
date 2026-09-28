package io.testforge.dispatcher.port.outbound;

import io.testforge.runorchestrator.task.model.ResourceMode;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 对齐 {@code contracts/schemas/v1/task-message.schema.json} 的不可变派发信封。
 *
 * <p>同一逻辑消息重投时必须复用 {@code messageId}；Redis 记录 ID 仅代表一次投递，
 * 不能作为业务幂等键或任务状态真相。</p>
 */
public record TaskDispatchMessage(
        String schemaVersion,
        UUID messageId,
        Instant publishedAt,
        Instant notBefore,
        int deliveryAttempt,
        UUID taskId,
        UUID runId,
        UUID attemptId,
        UUID leaseToken,
        String workerProtocol,
        Execution execution,
        Callbacks callbacks,
        String traceparent,
        String tracestate
) {
    public static final String SCHEMA_VERSION = "1.0.0";
    private static final Pattern WORKER_PROTOCOL = Pattern.compile("^1\\.[0-9]+$");
    private static final Pattern TRACEPARENT = Pattern.compile(
            "^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$"
    );
    private static final Pattern FEATURE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    public TaskDispatchMessage {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion 必须是 " + SCHEMA_VERSION);
        }
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        if (deliveryAttempt < 1) {
            throw new IllegalArgumentException("deliveryAttempt 必须大于等于 1");
        }
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        requirePattern(workerProtocol, WORKER_PROTOCOL, "workerProtocol");
        Objects.requireNonNull(execution, "execution must not be null");
        Objects.requireNonNull(callbacks, "callbacks must not be null");
        requirePattern(traceparent, TRACEPARENT, "traceparent");
        if (tracestate != null && tracestate.length() > 512) {
            throw new IllegalArgumentException("tracestate 长度不能超过 512");
        }
    }

    /**
     * 构造一次重投信封，保留 messageId 和全部业务身份。
     */
    public TaskDispatchMessage redelivery(int nextDeliveryAttempt, Instant republishedAt) {
        if (nextDeliveryAttempt <= deliveryAttempt) {
            throw new IllegalArgumentException("重投次数必须大于当前 deliveryAttempt");
        }
        return new TaskDispatchMessage(
                schemaVersion,
                messageId,
                republishedAt,
                notBefore,
                nextDeliveryAttempt,
                taskId,
                runId,
                attemptId,
                leaseToken,
                workerProtocol,
                execution,
                callbacks,
                traceparent,
                tracestate
        );
    }

    public record Execution(
            UUID projectId,
            UUID targetId,
            UUID workflowNodeId,
            String sourceType,
            String sourceRef,
            RunnerType runner,
            WorkerPlatform platform,
            List<String> requiredFeatures,
            TargetRevision targetRevision,
            Script script,
            Map<String, Object> parameters,
            Environment environment,
            int timeoutSeconds,
            UUID deviceSlotId
    ) {
        public Execution {
            Objects.requireNonNull(projectId, "projectId must not be null");
            Objects.requireNonNull(targetId, "targetId must not be null");
            Objects.requireNonNull(workflowNodeId, "workflowNodeId must not be null");
            if (!"ASSERTION".equals(sourceType) && !"FIXTURE".equals(sourceType)) {
                throw new IllegalArgumentException("sourceType 只支持 ASSERTION 或 FIXTURE");
            }
            requireLength(sourceRef, 1, 500, "sourceRef");
            Objects.requireNonNull(runner, "runner must not be null");
            Objects.requireNonNull(platform, "platform must not be null");
            requiredFeatures = List.copyOf(Objects.requireNonNull(
                    requiredFeatures,
                    "requiredFeatures must not be null"
            ));
            if (requiredFeatures.size() > 32 || requiredFeatures.stream().distinct().count() != requiredFeatures.size()) {
                throw new IllegalArgumentException("requiredFeatures 必须唯一且不能超过 32 项");
            }
            requiredFeatures.forEach(feature -> requirePattern(feature, FEATURE, "requiredFeature"));
            Objects.requireNonNull(script, "script must not be null");
            parameters = immutableMap(parameters, "parameters");
            Objects.requireNonNull(environment, "environment must not be null");
            if (timeoutSeconds < 1 || timeoutSeconds > 86_400) {
                throw new IllegalArgumentException("timeoutSeconds 必须在 1 到 86400 之间");
            }
            if (runner == RunnerType.AIRTEST) {
                if (platform == WorkerPlatform.ANY || platform == WorkerPlatform.LINUX) {
                    throw new IllegalArgumentException("airtest 只能路由到 WINDOWS、ANDROID 或 IOS");
                }
                Objects.requireNonNull(deviceSlotId, "airtest 的 deviceSlotId 不能为空");
            }
        }

        public Execution(
                UUID projectId,
                UUID targetId,
                UUID workflowNodeId,
                String sourceType,
                String sourceRef,
                RunnerType runner,
                WorkerPlatform platform,
                List<String> requiredFeatures,
                Script script,
                Map<String, Object> parameters,
                Environment environment,
                int timeoutSeconds,
                UUID deviceSlotId
        ) {
            this(
                    projectId, targetId, workflowNodeId, sourceType, sourceRef, runner, platform,
                    requiredFeatures, null, script, parameters, environment, timeoutSeconds, deviceSlotId
            );
        }

        public ResourceMode resourceMode() {
            return runner == RunnerType.AIRTEST
                    ? ResourceMode.EXCLUSIVE_DEVICE
                    : ResourceMode.PROCESS_POOL;
        }
    }

    public record TargetRevision(
            String repositoryUrl,
            String requestedType,
            String requestedValue,
            String resolvedCommit,
            Instant resolvedAt
    ) {
        private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{40}$");

        public TargetRevision {
            requireLength(repositoryUrl, 1, 2048, "targetRevision.repositoryUrl");
            if (!List.of("DEFAULT_BRANCH", "BRANCH", "TAG", "COMMIT").contains(requestedType)) {
                throw new IllegalArgumentException("targetRevision.requestedType 不合法");
            }
            if (requestedValue != null && requestedValue.length() > 255) {
                throw new IllegalArgumentException("targetRevision.requestedValue 长度不能超过 255");
            }
            requirePattern(resolvedCommit, COMMIT, "targetRevision.resolvedCommit");
            Objects.requireNonNull(resolvedAt, "targetRevision.resolvedAt must not be null");
        }
    }

    public record Script(int version, String sourceRef, String checksum) {
        private static final Pattern CHECKSUM = Pattern.compile("^sha256:[a-f0-9]{64}$");

        public Script {
            if (version < 1) {
                throw new IllegalArgumentException("script.version 必须大于等于 1");
            }
            requireLength(sourceRef, 1, 1024, "script.sourceRef");
            requirePattern(checksum, CHECKSUM, "script.checksum");
        }
    }

    public record Environment(
            UUID environmentId,
            String endpoint,
            Map<String, Object> config,
            Map<String, String> secretRefs
    ) {
        public Environment {
            Objects.requireNonNull(environmentId, "environmentId must not be null");
            requireLength(endpoint, 1, 2048, "environment.endpoint");
            config = immutableMap(config, "environment.config");
            secretRefs = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(
                    secretRefs,
                    "environment.secretRefs must not be null"
            )));
        }
    }

    public record Callbacks(
            String startUrl,
            String heartbeatUrl,
            String completeUrl,
            String artifactUploadUrl
    ) {
        public Callbacks {
            requireLength(startUrl, 1, 2048, "callbacks.startUrl");
            requireLength(heartbeatUrl, 1, 2048, "callbacks.heartbeatUrl");
            requireLength(completeUrl, 1, 2048, "callbacks.completeUrl");
            requireLength(artifactUploadUrl, 1, 2048, "callbacks.artifactUploadUrl");
        }
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source, String field) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(
                source,
                field + " must not be null"
        )));
    }

    private static void requireLength(String value, int minimum, int maximum, String field) {
        if (value == null || value.length() < minimum || value.length() > maximum) {
            throw new IllegalArgumentException(field + " 长度必须在 " + minimum + " 到 " + maximum + " 之间");
        }
    }

    private static void requirePattern(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 格式不正确");
        }
    }
}
