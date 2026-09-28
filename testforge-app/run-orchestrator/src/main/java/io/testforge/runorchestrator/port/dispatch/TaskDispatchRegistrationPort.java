package io.testforge.runorchestrator.port.dispatch;

/**
 * 由 dispatcher 实现。调用方必须在修改任务状态的同一数据库事务中登记派发事件。
 */
@FunctionalInterface
public interface TaskDispatchRegistrationPort {

    void register(TaskDispatchRegistration registration);

    static TaskDispatchRegistrationPort noop() {
        return registration -> {
        };
    }
}
