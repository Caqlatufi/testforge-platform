package io.testforge.dispatcher.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.dispatcher.port.outbound.DispatchObservationPort;
import io.testforge.dispatcher.port.outbound.DispatchOutcome;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RedisStreamDispatchAdapterTest {

    @Test
    void toleratesDuplicateDeliveryAndPreservesMessageIdentity() {
        var writes = new ArrayList<Write>();
        var ids = new AtomicInteger();
        RedisStreamWriter writer = (stream, fields) -> {
            writes.add(new Write(stream, fields));
            return ids.incrementAndGet() + "-0";
        };
        var telemetry = new DispatchTelemetry();
        var adapter = adapter(writer, telemetry, new MutableClock(TaskDispatchMessageFixtures.PUBLISHED_AT));
        var message = TaskDispatchMessageFixtures.pytestMessage();

        var first = adapter.dispatch(message);
        var duplicate = adapter.dispatch(message);
        var redelivery = adapter.dispatch(message.redelivery(2, message.publishedAt().plusSeconds(1)));

        assertThat(first.published()).isTrue();
        assertThat(duplicate.published()).isTrue();
        assertThat(redelivery.published()).isTrue();
        assertThat(writes).hasSize(3).allSatisfy(write -> {
            assertThat(write.stream()).isEqualTo("testforge:tasks:pytest-http");
            assertThat(write.fields()).containsEntry("messageId", message.messageId().toString());
        });
        assertThat(first.redisRecordId()).isNotEqualTo(duplicate.redisRecordId());
        assertThat(telemetry.snapshot().published()).isEqualTo(3);
        assertThat(telemetry.snapshot().redeliveries()).isEqualTo(1);
    }

    @Test
    void pausesDuringRedisOutageAndRecoversWithOneProbeAfterCooldown() {
        var calls = new AtomicInteger();
        RedisStreamWriter writer = (stream, fields) -> {
            if (calls.getAndIncrement() == 0) {
                throw new RedisConnectionFailureException("connection refused");
            }
            return "2-0";
        };
        var telemetry = new DispatchTelemetry();
        var clock = new MutableClock(TaskDispatchMessageFixtures.PUBLISHED_AT);
        var adapter = adapter(writer, telemetry, clock);
        var message = TaskDispatchMessageFixtures.pytestMessage();

        var unavailable = adapter.dispatch(message);
        var paused = adapter.dispatch(message.redelivery(2, clock.instant()));

        assertThat(unavailable.outcome()).isEqualTo(DispatchOutcome.REDIS_UNAVAILABLE);
        assertThat(unavailable.retryable()).isTrue();
        assertThat(paused.outcome()).isEqualTo(DispatchOutcome.PAUSED);
        assertThat(calls).hasValue(1);
        assertThat(adapter.availability().state()).isEqualTo(RedisDispatchState.PAUSED);

        clock.advance(Duration.ofSeconds(5));
        var recovered = adapter.dispatch(message.redelivery(3, clock.instant()));

        assertThat(recovered.published()).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(adapter.availability().state()).isEqualTo(RedisDispatchState.ACTIVE);
        assertThat(telemetry.snapshot().redisUnavailable()).isEqualTo(1);
        assertThat(telemetry.snapshot().paused()).isEqualTo(1);
        assertThat(telemetry.snapshot().recoveries()).isEqualTo(1);
        assertThat(telemetry.snapshot().lastObservation().connectionRecovered()).isTrue();
    }

    @Test
    void redisExceptionFallsBackToRetryableResultWithoutLeakingPayload() {
        RedisStreamWriter writer = (stream, fields) -> {
            throw new RedisConnectionFailureException("socket closed");
        };
        var adapter = adapter(
                writer,
                DispatchObservationPort.noop(),
                new MutableClock(TaskDispatchMessageFixtures.PUBLISHED_AT)
        );

        var result = adapter.dispatch(TaskDispatchMessageFixtures.pytestMessage());

        assertThat(result.outcome()).isEqualTo(DispatchOutcome.REDIS_UNAVAILABLE);
        assertThat(result.retryable()).isTrue();
        assertThat(result.redisRecordId()).isNull();
        assertThat(result.detail())
                .contains("RedisConnectionFailureException")
                .doesNotContain("apiToken")
                .doesNotContain("secret/testforge");
    }

    @Test
    void observationFailureDoesNotChangeSuccessfulPublish() {
        var adapter = adapter(
                (stream, fields) -> "9-0",
                observation -> {
                    throw new IllegalStateException("metrics backend unavailable");
                },
                new MutableClock(TaskDispatchMessageFixtures.PUBLISHED_AT)
        );

        assertThat(adapter.dispatch(TaskDispatchMessageFixtures.pytestMessage()).published()).isTrue();
    }

    private RedisStreamDispatchAdapter adapter(
            RedisStreamWriter writer,
            DispatchObservationPort observationPort,
            Clock clock
    ) {
        return new RedisStreamDispatchAdapter(
                writer,
                new TaskMessageEncoder(new ObjectMapper()),
                new RedisStreamRouteResolver(),
                new RedisDispatchAvailability(new RedisDispatchPolicy(Duration.ofSeconds(5))),
                observationPort,
                clock
        );
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
