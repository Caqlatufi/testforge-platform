package io.testforge.dispatcher.port.outbound;

/**
 * dispatcher Relay 向消息设施投递 Worker 任务的出站端口。
 */
@FunctionalInterface
public interface TaskDispatchPort {

    DispatchResult dispatch(TaskDispatchMessage message);
}
