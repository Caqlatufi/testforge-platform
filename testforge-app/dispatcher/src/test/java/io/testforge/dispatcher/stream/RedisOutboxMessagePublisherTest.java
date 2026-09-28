package io.testforge.dispatcher.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.dispatcher.port.outbound.OutboxMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisOutboxMessagePublisherTest {

    private static final Instant NOW = Instant.parse("2026-09-18T08:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");

    @Test
    void routesTaskReadyEventsAndKeepsStableIdentityAcrossDuplicateDelivery() {
        List<Write> writes = new ArrayList<>();
        RedisStreamWriter writer = (stream, fields) -> {
            writes.add(new Write(stream, fields));
            return writes.size() + "-0";
        };
        var telemetry = new DispatchTelemetry();
        var publisher = publisher(writer, telemetry, new MutableClock(NOW));

        publisher.publish(message("pytest-http", "linux", 1));
        publisher.publish(message("pytest-http", "linux", 2));

        assertThat(writes).hasSize(2).allSatisfy(write -> {
            assertThat(write.stream()).isEqualTo("testforge:tasks:pytest-http");
            assertThat(write.fields())
                    .containsEntry("messageId", EVENT_ID.toString())
                    .containsEntry("eventKey", "task-ready:" + TASK_ID)
                    .containsEntry("taskId", TASK_ID.toString())
                    .containsEntry("runId", RUN_ID.toString());
        });
        assertThat(writes.get(0).fields()).containsEntry("deliveryAttempt", "1");
        assertThat(writes.get(1).fields()).containsEntry("deliveryAttempt", "2");
        assertThat(telemetry.snapshot().published()).isEqualTo(2);
        assertThat(telemetry.snapshot().redeliveries()).isEqualTo(1);
    }

    @Test
    void routesAirtestByConcretePlatform() {
        List<Write> writes = new ArrayList<>();
        var publisher = publisher(
                (stream, fields) -> {
                    writes.add(new Write(stream, fields));
                    return "1-0";
                },
                new DispatchTelemetry(),
                new MutableClock(NOW)
        );

        publisher.publish(message("airtest", "windows", 1));

        assertThat(writes).singleElement().satisfies(write ->
                assertThat(write.stream()).isEqualTo("testforge:tasks:airtest:windows")
        );
    }

    @Test
    void pausesOnRedisFailureAndPublishesAfterRecoveryProbe() {
        AtomicInteger calls = new AtomicInteger();
        List<Write> writes = new ArrayList<>();
        RedisStreamWriter writer = (stream, fields) -> {
            if (calls.getAndIncrement() == 0) {
                throw new RedisConnectionFailureException("connection refused");
            }
            writes.add(new Write(stream, fields));
            return "2-0";
        };
        var clock = new MutableClock(NOW);
        var telemetry = new DispatchTelemetry();
        var publisher = publisher(writer, telemetry, clock);

        assertThatThrownBy(() -> publisher.publish(message("pytest-http", "linux", 1)))
                .isInstanceOf(RedisDispatchUnavailableException.class)
                .hasMessageContaining("connection refused");
        assertThatThrownBy(() -> publisher.publish(message("pytest-http", "linux", 2)))
                .isInstanceOf(RedisDispatchUnavailableException.class)
                .hasMessageContaining("暂停窗口");
        assertThat(calls).hasValue(1);
        assertThat(publisher.availability().state()).isEqualTo(RedisDispatchState.PAUSED);

        clock.advance(Duration.ofSeconds(5));
        publisher.publish(message("pytest-http", "linux", 3));

        assertThat(calls).hasValue(2);
        assertThat(writes).singleElement().satisfies(write ->
                assertThat(write.fields()).containsEntry("deliveryAttempt", "3")
        );
        assertThat(publisher.availability().state()).isEqualTo(RedisDispatchState.ACTIVE);
        assertThat(telemetry.snapshot().redisUnavailable()).isEqualTo(1);
        assertThat(telemetry.snapshot().paused()).isEqualTo(1);
        assertThat(telemetry.snapshot().recoveries()).isEqualTo(1);
    }

    @Test
    void rejectsPayloadWhoseTaskIdentityDoesNotMatchAggregate() {
        var publisher = publisher(
                (stream, fields) -> "1-0",
                new DispatchTelemetry(),
                new MutableClock(NOW)
        );
        String payload = payload(UUID.randomUUID(), "pytest-http", "linux");
        var message = new OutboxMessage(
                EVENT_ID,
                "task-ready:" + TASK_ID,
                TASK_ID,
                "TASK",
                "TASK_READY",
                payload,
                1
        );

        assertThatThrownBy(() -> publisher.publish(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateId");
    }

    private RedisOutboxMessagePublisher publisher(
            RedisStreamWriter writer,
            DispatchTelemetry telemetry,
            Clock clock
    ) {
        return new RedisOutboxMessagePublisher(
                writer,
                new RedisStreamRouteResolver(),
                new RedisDispatchAvailability(new RedisDispatchPolicy(Duration.ofSeconds(5))),
                telemetry,
                new ObjectMapper(),
                clock
        );
    }

    private OutboxMessage message(String runner, String platform, int deliveryAttempt) {
        return new OutboxMessage(
                EVENT_ID,
                "task-ready:" + TASK_ID,
                TASK_ID,
                "TASK",
                "TASK_READY",
                payload(TASK_ID, runner, platform),
                deliveryAttempt
        );
    }

    private String payload(UUID taskId, String runner, String platform) {
        return """
                {
                  "schemaVersion": 1,
                  "taskId": "%s",
                  "runId": "%s",
                  "runner": "%s",
                  "platform": "%s",
                  "queuedAt": "%s"
                }
                """.formatted(taskId, RUN_ID, runner, platform, NOW);
    }

    private record Write(String stream, Map<String, String> fields) {
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
