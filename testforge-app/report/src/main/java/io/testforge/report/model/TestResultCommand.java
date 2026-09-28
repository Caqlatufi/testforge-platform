package io.testforge.report.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 提交给报告模块的确定性测试结果命令。
 *
 * <p>该 DTO 只定义模块契约，不执行失败分类、统计或持久化。</p>
 */
public record TestResultCommand(
        UUID taskId,
        Optional<UUID> attemptId,
        String status,
        long durationMs,
        Optional<String> failureType,
        String summary,
        List<String> artifactKeys,
        Optional<String> blockedBy) {

    public TestResultCommand {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(failureType, "failureType");
        Objects.requireNonNull(summary, "summary");
        artifactKeys = List.copyOf(Objects.requireNonNull(artifactKeys, "artifactKeys"));
        Objects.requireNonNull(blockedBy, "blockedBy");
    }
}
