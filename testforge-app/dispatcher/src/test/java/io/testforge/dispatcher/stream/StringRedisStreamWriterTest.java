package io.testforge.dispatcher.stream;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StringRedisStreamWriterTest {

    @Test
    @SuppressWarnings("unchecked")
    void appendsMapRecordAndReturnsRedisRecordId() {
        var template = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> operations = mock(StreamOperations.class);
        when(template.opsForStream()).thenReturn(operations);
        when(operations.add(any(MapRecord.class))).thenReturn(RecordId.of("123-4"));
        var writer = new StringRedisStreamWriter(template);

        var recordId = writer.append("testforge:tasks:pytest-http", Map.of("messageId", "message-1"));

        assertThat(recordId).isEqualTo("123-4");
        verify(operations).add(any(MapRecord.class));
    }
}
