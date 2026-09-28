package io.testforge.dispatcher.stream;

import java.util.Map;

/**
 * Redis Stream 的最小写入边界，便于在不启动 Redis 的单元测试中验证恢复语义。
 */
@FunctionalInterface
public interface RedisStreamWriter {

    String append(String stream, Map<String, String> fields);
}
