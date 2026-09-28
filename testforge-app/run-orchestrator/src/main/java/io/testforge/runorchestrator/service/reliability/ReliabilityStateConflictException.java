package io.testforge.runorchestrator.service.reliability;

/** 双版本 CAS 任一侧失败时抛出，使事务整体回滚。 */
public final class ReliabilityStateConflictException extends RuntimeException {
    public ReliabilityStateConflictException() {
        super("Task/Attempt 可靠性状态已被并发修改");
    }
}
