package io.testforge.dispatcher.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.testforge.dispatcher.port.outbound.TaskDispatchMessage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 将完整 JSON 信封与常用索引字段一起写入 Stream record。
 */
public final class TaskMessageEncoder {

    private final ObjectMapper objectMapper;

    public TaskMessageEncoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null")
                .copy()
                .findAndRegisterModules()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public Map<String, String> encode(TaskDispatchMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("schemaVersion", message.schemaVersion());
            fields.put("messageId", message.messageId().toString());
            fields.put("taskId", message.taskId().toString());
            fields.put("runId", message.runId().toString());
            fields.put("attemptId", message.attemptId().toString());
            fields.put("runner", message.execution().runner().contractValue());
            fields.put("platform", message.execution().platform().contractValue());
            fields.put("resourceMode", message.execution().resourceMode().name());
            fields.put("deliveryAttempt", Integer.toString(message.deliveryAttempt()));
            fields.put("traceparent", message.traceparent());
            fields.put("payload", objectMapper.writeValueAsString(message));
            return Map.copyOf(fields);
        } catch (JsonProcessingException exception) {
            throw new TaskMessageEncodingException(
                    "任务消息序列化失败: messageId=" + message.messageId(),
                    exception
            );
        }
    }
}
