package io.testforge.runorchestrator.service.scheduling;

import io.testforge.runorchestrator.run.entity.TestRunEntity;
import io.testforge.runorchestrator.run.model.RunState;
import io.testforge.runorchestrator.run.service.RunAggregationPolicy;
import io.testforge.runorchestrator.task.entity.TestTaskEntity;

import java.util.List;
import java.util.Objects;

/**
 * 在调度事务内聚合 Run。Run 一旦开始执行，即使暂时只剩排队任务也不回退到 QUEUED。
 */
public final class RunSchedulingAggregation {

    private final RunAggregationPolicy policy;

    public RunSchedulingAggregation() {
        this(new RunAggregationPolicy());
    }

    RunSchedulingAggregation(RunAggregationPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public RunState determine(TestRunEntity run, List<TestTaskEntity> tasks) {
        Objects.requireNonNull(run, "run must not be null");
        RunState aggregate = policy.determine(run.getCancellationRequestedAt() != null, tasks);
        if (run.getState() == RunState.RUNNING && aggregate == RunState.QUEUED) {
            return RunState.RUNNING;
        }
        return aggregate;
    }
}
