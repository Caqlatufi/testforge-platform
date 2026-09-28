package io.testforge.dispatcher.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.dispatcher.port.outbound.DispatchObservation;
import io.testforge.dispatcher.port.outbound.DispatchObservationPort;
import io.testforge.dispatcher.port.outbound.DispatchOutcome;
import io.testforge.dispatcher.port.outbound.DispatchResult;
import io.testforge.dispatcher.port.outbound.TaskDispatchMessage;
import io.testforge.dispatcher.port.outbound.TaskDispatchPort;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Redis Streams 派发适配器。
 *
 * <p>Redis 故障只返回可重试结果，不改变 MySQL 中的 Task/Attempt/Outbox 状态；
 * 是否标记 Outbox 已发布由调用方依据 {@link DispatchResult#published()} 决定。</p>
 */
public final class RedisStreamDispatchAdapter implements TaskDispatchPort {

    private final RedisStreamRouteResolver routeResolver;
    private final TaskMessageEncoder encoder;
    private final RedisStreamWriter streamWriter;
    private final RedisDispatchAvailability availability;
    private final DispatchObservationPort observationPort;
    private final Clock clock;

    public RedisStreamDispatchAdapter(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RedisStreamRouteResolver routeResolver,
            RedisDispatchPolicy policy,
            DispatchObservationPort observationPort,
            Clock clock
    ) {
        this(
                new StringRedisStreamWriter(redisTemplate),
                new TaskMessageEncoder(objectMapper),
                routeResolver,
                new RedisDispatchAvailability(policy),
                observationPort,
                clock
        );
    }

    public RedisStreamDispatchAdapter(
            RedisStreamWriter streamWriter,
            TaskMessageEncoder encoder,
            RedisStreamRouteResolver routeResolver,
            RedisDispatchAvailability availability,
            DispatchObservationPort observationPort,
            Clock clock
    ) {
        this.streamWriter = Objects.requireNonNull(streamWriter, "streamWriter must not be null");
        this.encoder = Objects.requireNonNull(encoder, "encoder must not be null");
        this.routeResolver = Objects.requireNonNull(routeResolver, "routeResolver must not be null");
        this.availability = Objects.requireNonNull(availability, "availability must not be null");
        this.observationPort = Objects.requireNonNull(observationPort, "observationPort must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public DispatchResult dispatch(TaskDispatchMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        String stream = routeResolver.resolve(message);
        Map<String, String> fields = encoder.encode(message);
        Instant now = clock.instant();
        RedisDispatchAvailability.Permit permit = availability.acquire(now);
        if (!permit.allowed()) {
            DispatchResult result = DispatchResult.paused(stream, permit.retryAt(), permit.detail());
            observe(message, result, false, now);
            return result;
        }

        try {
            String redisRecordId = streamWriter.append(stream, fields);
            boolean recovered = availability.markAvailable();
            DispatchResult result = DispatchResult.published(stream, redisRecordId);
            observe(message, result, recovered, now);
            return result;
        } catch (DataAccessException exception) {
            String detail = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
            Instant retryAt = availability.pause(now, detail);
            DispatchResult result = DispatchResult.unavailable(stream, retryAt, detail);
            observe(message, result, false, now);
            return result;
        }
    }

    public RedisDispatchAvailabilitySnapshot availability() {
        return availability.snapshot();
    }

    private void observe(
            TaskDispatchMessage message,
            DispatchResult result,
            boolean recovered,
            Instant observedAt
    ) {
        try {
            observationPort.observe(new DispatchObservation(
                    message.messageId(),
                    message.taskId(),
                    result.stream(),
                    message.deliveryAttempt(),
                    result.outcome(),
                    recovered,
                    observedAt,
                    result.detail()
            ));
        } catch (RuntimeException ignored) {
            // 可观测性是旁路能力，不能把已完成的 XADD 变成 Relay 失败。
        }
    }

    private static String safeMessage(DataAccessException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Redis data access failed" : message;
    }
}
