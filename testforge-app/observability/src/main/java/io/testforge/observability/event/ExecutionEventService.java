package io.testforge.observability.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.common.event.ExecutionEventCommand;
import io.testforge.common.event.ExecutionEventPort;
import io.testforge.common.event.ExecutionEventQueryPort;
import io.testforge.common.event.ExecutionEventView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ExecutionEventService implements ExecutionEventPort, ExecutionEventQueryPort {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final ExecutionEventRepository repository;
    private final ObjectMapper objectMapper;

    public ExecutionEventService(ExecutionEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void append(ExecutionEventCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (repository.findByEventKey(command.eventKey()).isPresent()) return;
        try {
            repository.saveAndFlush(new ExecutionEventEntity(
                    Objects.requireNonNull(command.eventKey()), Objects.requireNonNull(command.runId()),
                    command.taskId(), command.attemptId(), requireType(command.type()),
                    Objects.requireNonNull(command.occurredAt()), write(command.payload())
            ));
        } catch (DataIntegrityViolationException duplicate) {
            if (repository.findByEventKey(command.eventKey()).isEmpty()) throw duplicate;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExecutionEventView> history(UUID runId, long afterEventId, int limit) {
        if (afterEventId < 0) throw new IllegalArgumentException("afterEventId 不能小于 0");
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit 必须在 1 到 500 之间");
        return repository.findByRunIdAndIdGreaterThanOrderByIdAsc(
                Objects.requireNonNull(runId), afterEventId, PageRequest.of(0, limit)
        ).stream().map(this::view).toList();
    }

    private ExecutionEventView view(ExecutionEventEntity entity) {
        return new ExecutionEventView(
                entity.getId(), entity.getEventKey(), entity.getRunId(), entity.getTaskId(),
                entity.getAttemptId(), entity.getType(), entity.getOccurredAt(), read(entity.getPayloadJson())
        );
    }

    private String requireType(String type) {
        if (type == null || !type.matches("^[A-Z][A-Z0-9_]{1,63}$")) {
            throw new IllegalArgumentException("事件类型格式不正确");
        }
        return type;
    }

    private String write(Map<String, Object> payload) {
        try { return objectMapper.writeValueAsString(payload); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("事件载荷无法序列化", error); }
    }

    private Map<String, Object> read(String payload) {
        try { return objectMapper.readValue(payload, MAP_TYPE); }
        catch (JsonProcessingException error) { throw new IllegalStateException("事件载荷无法读取", error); }
    }
}
