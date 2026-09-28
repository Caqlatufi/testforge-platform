package io.testforge.runorchestrator.service.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 从持久化 QUEUED Task 中选择公平候选。该结果只是可过期的排序快照；TFP-009 集成层
 * 必须继续调用配额服务，并在同一事务中登记派发请求，不能把候选查询当成领取成功。
 */
public class RunSchedulingService {

    private final SchedulingCandidateRepository candidateRepository;
    private final FairSchedulingPolicy schedulingPolicy;

    public RunSchedulingService(SchedulingCandidateRepository candidateRepository) {
        this(candidateRepository, new FairSchedulingPolicy());
    }

    RunSchedulingService(
            SchedulingCandidateRepository candidateRepository,
            FairSchedulingPolicy schedulingPolicy
    ) {
        this.candidateRepository = Objects.requireNonNull(
                candidateRepository,
                "candidateRepository must not be null"
        );
        this.schedulingPolicy = Objects.requireNonNull(schedulingPolicy, "schedulingPolicy must not be null");
    }

    public List<SchedulingCandidate> select(int maxCandidates, Instant scheduledAt) {
        if (maxCandidates < 1) {
            throw new IllegalArgumentException("maxCandidates 必须大于 0");
        }
        Objects.requireNonNull(scheduledAt, "scheduledAt must not be null");

        List<SchedulingCandidate> candidates = schedulingPolicy.order(
                candidateRepository.findQueuedCandidates(scheduledAt),
                scheduledAt
        );
        return candidates.size() <= maxCandidates
                ? candidates
                : List.copyOf(candidates.subList(0, maxCandidates));
    }
}
