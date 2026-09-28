package io.testforge.dispatcher.stream;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataRetrievalFailureException;

import java.util.Map;
import java.util.Objects;

/**
 * 基于 Spring Data Redis 的 Stream 写入器。
 */
public final class StringRedisStreamWriter implements RedisStreamWriter {

    private final StringRedisTemplate redisTemplate;

    public StringRedisStreamWriter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
    }

    @Override
    public String append(String stream, Map<String, String> fields) {
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(stream)
                .ofMap(fields);
        RecordId recordId = redisTemplate.opsForStream().add(record);
        if (recordId == null) {
            throw new DataRetrievalFailureException("Redis XADD 未返回 record id");
        }
        return recordId.getValue();
    }
}
