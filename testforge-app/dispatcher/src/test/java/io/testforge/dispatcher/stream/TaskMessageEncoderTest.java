package io.testforge.dispatcher.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TaskMessageEncoderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final TaskMessageEncoder encoder = new TaskMessageEncoder(objectMapper);

    @Test
    void encodesContractEnvelopeAndIndexFieldsWithoutOptionalNulls() throws Exception {
        var message = TaskDispatchMessageFixtures.pytestMessage();

        var fields = encoder.encode(message);
        var payload = objectMapper.readTree(fields.get("payload"));

        assertThat(fields)
                .containsEntry("messageId", message.messageId().toString())
                .containsEntry("runner", "pytest-http")
                .containsEntry("platform", "LINUX")
                .containsEntry("deliveryAttempt", "1");
        assertThat(payload.path("publishedAt").asText()).isEqualTo("2026-09-18T01:02:03Z");
        assertThat(payload.has("notBefore")).isFalse();
        assertThat(payload.has("tracestate")).isFalse();
        assertThat(payload.path("execution").path("runner").asText()).isEqualTo("pytest-http");
        assertThat(payload.path("execution").path("environment").path("secretRefs").path("apiToken").asText())
                .isEqualTo("secret/testforge/demo-token");
    }

    @Test
    void redeliveryKeepsLogicalMessageIdentity() {
        var original = TaskDispatchMessageFixtures.pytestMessage();
        var redelivery = original.redelivery(2, Instant.parse("2026-09-18T01:03:03Z"));

        assertThat(redelivery.messageId()).isEqualTo(original.messageId());
        assertThat(redelivery.taskId()).isEqualTo(original.taskId());
        assertThat(redelivery.attemptId()).isEqualTo(original.attemptId());
        assertThat(redelivery.deliveryAttempt()).isEqualTo(2);
        assertThat(encoder.encode(redelivery)).containsEntry("messageId", original.messageId().toString());
    }
}
