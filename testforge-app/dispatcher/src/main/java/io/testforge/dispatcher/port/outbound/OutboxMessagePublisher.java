package io.testforge.dispatcher.port.outbound;

/**
 * 消息系统出站端口。实现必须使用 eventId/eventKey 作为下游去重依据，并允许重复投递。
 */
@FunctionalInterface
public interface OutboxMessagePublisher {

    void publish(OutboxMessage message) throws Exception;
}
