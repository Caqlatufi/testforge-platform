package io.testforge.runorchestrator.task.dag.release;

import java.util.UUID;

/**
 * DAG 释放器的持久化边界。
 *
 * <p>实现必须使用 Task 的状态与版本做原子比较更新。进入 QUEUED 后的派发登记应由接线层
 * 与该更新放在同一事务中，保证只有成功更新状态的调用会登记一次派发。</p>
 */
public interface DagReleaseStateStore {

    DagRunSnapshot load(UUID runId);

    boolean compareAndSet(UUID runId, DagTaskTransition transition);
}
