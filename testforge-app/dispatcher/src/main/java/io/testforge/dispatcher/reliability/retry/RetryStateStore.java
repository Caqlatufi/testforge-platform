package io.testforge.dispatcher.reliability.retry;

import java.util.UUID;

/**
 * 由 run-orchestrator 的公开可靠性状态服务提供持久化适配。
 * 实现必须用 expectedSnapshot 中的 Task/Attempt 版本做同事务 CAS。
 */
public interface RetryStateStore {

    RetryStateSnapshot load(UUID taskId);

    boolean compareAndSet(RetryStateSnapshot expectedSnapshot, RetryTransition transition);
}
