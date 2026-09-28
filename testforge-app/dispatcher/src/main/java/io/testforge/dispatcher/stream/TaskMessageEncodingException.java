package io.testforge.dispatcher.stream;

/**
 * 任务信封无法序列化时的非瞬时异常。Relay 不应把此异常误判为 Redis 暂时不可用。
 */
public final class TaskMessageEncodingException extends RuntimeException {

    public TaskMessageEncodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
