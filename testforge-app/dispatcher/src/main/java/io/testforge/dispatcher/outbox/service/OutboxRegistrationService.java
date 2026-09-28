package io.testforge.dispatcher.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.testforge.dispatcher.outbox.entity.OutboxEventEntity;
import io.testforge.dispatcher.outbox.model.OutboxEventView;
import io.testforge.dispatcher.outbox.repo.OutboxEventRepository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 在调用方现有事务中登记 Outbox；没有外层事务时会创建一个本地事务。
 */
public class OutboxRegistrationService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;

    public OutboxRegistrationService(
            OutboxEventRepository repository,
            ObjectMapper objectMapper,
            Clock clock,
            Supplier<UUID> idGenerator
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null").copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
    }

    @Transactional
    public OutboxEventView register(
            String eventKey,
            UUID aggregateId,
            String aggregateType,
            String eventType,
            Object payload
    ) {
        return registerAvailableAt(
                eventKey,
                aggregateId,
                aggregateType,
                eventType,
                payload,
                Instant.now(clock)
        );
    }

    @Transactional
    public OutboxEventView registerAvailableAt(
            String eventKey,
            UUID aggregateId,
            String aggregateType,
            String eventType,
            Object payload,
            Instant availableAt
    ) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(availableAt, "availableAt must not be null");
        String payloadJson = writePayload(payload);
        String payloadHash = sha256(payloadJson);

        var existing = repository.findByEventKey(eventKey);
        if (existing.isPresent()) {
            OutboxEventEntity event = existing.get();
            if (!event.hasSameFingerprint(aggregateId, eventType, payloadHash)) {
                throw new OutboxIdempotencyConflictException("相同 eventKey 对应不同事件内容: " + eventKey);
            }
            return event.toView();
        }

        Instant now = Instant.now(clock);
        return repository.save(new OutboxEventEntity(
                idGenerator.get(),
                eventKey,
                aggregateId,
                aggregateType,
                eventType,
                payloadJson,
                payloadHash,
                now,
                availableAt
        )).toView();
    }

    private String writePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Outbox payload 无法序列化", exception);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }
}
