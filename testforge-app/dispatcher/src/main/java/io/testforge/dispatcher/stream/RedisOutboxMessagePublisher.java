package io.testforge.dispatcher.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.dispatcher.port.outbound.DispatchObservation;
import io.testforge.dispatcher.port.outbound.DispatchObservationPort;
import io.testforge.dispatcher.port.outbound.DispatchOutcome;
import io.testforge.dispatcher.port.outbound.OutboxMessage;
import io.testforge.dispatcher.port.outbound.OutboxMessagePublisher;
import io.testforge.dispatcher.port.outbound.RunnerType;
import io.testforge.dispatcher.port.outbound.WorkerPlatform;
import io.testforge.runorchestrator.task.model.ResourceMode;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 将事务 Outbox 中的 TASK_READY 事件写入按 runner/platform 隔离的 Redis Stream。
 *
 * <p>Stream record 使用 Outbox eventId 作为稳定 messageId。Redis 接收消息但 MySQL 尚未标记
 * PUBLISHED 时发生崩溃，Relay 会重复 XADD，但重投记录仍携带相同 messageId/taskId，消费方可据此
 * 在 MySQL 状态与版本条件更新上幂等领取。</p>
 */
public final class RedisOutboxMessagePublisher implements OutboxMessagePublisher {

    private static final String TASK_AGGREGATE_TYPE = "TASK";
    private static final String TASK_READY_EVENT_TYPE = "TASK_READY";

    private final RedisStreamWriter streamWriter;
    private final RedisStreamRouteResolver routeResolver;
    private final RedisDispatchAvailability availability;
    private final DispatchObservationPort observationPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RedisOutboxMessagePublisher(
            RedisStreamWriter streamWriter,
            RedisStreamRouteResolver routeResolver,
            RedisDispatchAvailability availability,
            DispatchObservationPort observationPort,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.streamWriter = Objects.requireNonNull(streamWriter, "streamWriter must not be null");
        this.routeResolver = Objects.requireNonNull(routeResolver, "routeResolver must not be null");
        this.availability = Objects.requireNonNull(availability, "availability must not be null");
        this.observationPort = Objects.requireNonNull(observationPort, "observationPort must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null").copy()
                .findAndRegisterModules();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void publish(OutboxMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        TaskReadyPayload task = decode(message);
        String stream = routeResolver.resolve(task.resourceMode(), task.runner(), task.platform());
        Instant now = clock.instant();
        RedisDispatchAvailability.Permit permit = availability.acquire(now);
        if (!permit.allowed()) {
            observe(message, task.taskId(), stream, DispatchOutcome.PAUSED, false, now, permit.detail());
            throw new RedisDispatchUnavailableException(
                    "Redis 派发处于暂停窗口: " + safeDetail(permit.detail()),
                    permit.retryAt()
            );
        }

        try {
            streamWriter.append(stream, fields(message, task, now));
            boolean recovered = availability.markAvailable();
            observe(message, task.taskId(), stream, DispatchOutcome.PUBLISHED, recovered, now, "published");
        } catch (DataAccessException exception) {
            String detail = exception.getClass().getSimpleName() + ": " + safeDetail(exception.getMessage());
            Instant retryAt = availability.pause(now, detail);
            observe(message, task.taskId(), stream, DispatchOutcome.REDIS_UNAVAILABLE, false, now, detail);
            throw new RedisDispatchUnavailableException(detail, retryAt, exception);
        }
    }

    public RedisDispatchAvailabilitySnapshot availability() {
        return availability.snapshot();
    }

    private TaskReadyPayload decode(OutboxMessage message) {
        if (!TASK_AGGREGATE_TYPE.equals(message.aggregateType())
                || !TASK_READY_EVENT_TYPE.equals(message.eventType())) {
            throw new IllegalArgumentException(
                    "Redis task publisher 只接受 TASK/TASK_READY 事件: " + message.eventKey()
            );
        }
        try {
            JsonNode payload = objectMapper.readTree(message.payload());
            UUID taskId = requiredUuid(payload, "taskId");
            if (!taskId.equals(message.aggregateId())) {
                throw new IllegalArgumentException("Outbox aggregateId 与 payload.taskId 不一致");
            }
            RunnerType runner = RunnerType.fromContractValue(requiredText(payload, "runner"));
            JsonNode resourceModeNode = payload.get("resourceMode");
            ResourceMode resourceMode = resourceModeNode == null || resourceModeNode.isNull()
                    ? ResourceMode.fromRunner(runner.contractValue())
                    : ResourceMode.valueOf(resourceModeNode.asText());
            JsonNode platformNode = payload.get("platform");
            WorkerPlatform platform = platformNode == null || platformNode.isNull()
                    ? WorkerPlatform.ANY
                    : WorkerPlatform.fromContractValue(platformNode.asText());
            return new TaskReadyPayload(
                    taskId,
                    requiredUuid(payload, "runId"),
                    runner,
                    resourceMode,
                    platform,
                    requiredInstant(payload, "queuedAt")
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("TASK_READY payload 不是合法 JSON: " + message.eventKey(), exception);
        }
    }

    private Map<String, String> fields(OutboxMessage message, TaskReadyPayload task, Instant publishedAt) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", "1.0.0");
        fields.put("messageType", TASK_READY_EVENT_TYPE);
        fields.put("messageId", message.eventId().toString());
        fields.put("eventKey", message.eventKey());
        fields.put("taskId", task.taskId().toString());
        fields.put("runId", task.runId().toString());
        fields.put("runner", task.runner().contractValue());
        fields.put("resourceMode", task.resourceMode().name());
        fields.put("platform", task.platform().contractValue());
        fields.put("deliveryAttempt", Integer.toString(message.deliveryAttempt()));
        fields.put("queuedAt", task.queuedAt().toString());
        fields.put("publishedAt", publishedAt.toString());
        fields.put("payload", message.payload());
        return Map.copyOf(fields);
    }

    private void observe(
            OutboxMessage message,
            UUID taskId,
            String stream,
            DispatchOutcome outcome,
            boolean recovered,
            Instant observedAt,
            String detail
    ) {
        try {
            observationPort.observe(new DispatchObservation(
                    message.eventId(),
                    taskId,
                    stream,
                    message.deliveryAttempt(),
                    outcome,
                    recovered,
                    observedAt,
                    detail
            ));
        } catch (RuntimeException ignored) {
            // 观测属于旁路能力，不能把已完成的 XADD 变成 Relay 失败。
        }
    }

    private static String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("TASK_READY payload 缺少字段: " + field);
        }
        return value.asText();
    }

    private static UUID requiredUuid(JsonNode payload, String field) {
        try {
            return UUID.fromString(requiredText(payload, field));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("TASK_READY payload UUID 字段非法: " + field, exception);
        }
    }

    private static Instant requiredInstant(JsonNode payload, String field) {
        try {
            return Instant.parse(requiredText(payload, field));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("TASK_READY payload 时间字段非法: " + field, exception);
        }
    }

    private static String safeDetail(String detail) {
        return detail == null || detail.isBlank() ? "Redis data access failed" : detail;
    }

    private record TaskReadyPayload(
            UUID taskId,
            UUID runId,
            RunnerType runner,
            ResourceMode resourceMode,
            WorkerPlatform platform,
            Instant queuedAt
    ) {
    }
}
