package io.testforge.runorchestrator.service.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 先用等待时长提升有效优先级，再在 Run 之间逐轮各取一个 Task。
 * 默认每等待 30 秒提升一级，最低优先级最多 270 秒即可追平优先级 9，
 * 同优先级下等待更久者优先，从而给出可解释的无饥饿边界。
 */
public final class FairSchedulingPolicy {

    public static final Duration DEFAULT_AGING_INTERVAL = Duration.ofSeconds(30);
    private static final int MAX_PRIORITY = 9;

    private final Duration agingInterval;

    public FairSchedulingPolicy() {
        this(DEFAULT_AGING_INTERVAL);
    }

    public FairSchedulingPolicy(Duration agingInterval) {
        this.agingInterval = Objects.requireNonNull(agingInterval, "agingInterval must not be null");
        if (agingInterval.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("agingInterval 不能小于 1 秒");
        }
    }

    public List<SchedulingCandidate> order(List<SchedulingCandidate> candidates, Instant now) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(now, "now must not be null");

        Map<UUID, Deque<SchedulingCandidate>> byRun = candidates.stream()
                .sorted(Comparator.comparing(SchedulingCandidate::queuedAt)
                        .thenComparingInt(SchedulingCandidate::sequenceNo)
                        .thenComparing(candidate -> candidate.taskId().toString()))
                .collect(Collectors.groupingBy(
                        SchedulingCandidate::runId,
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayDeque::new)
                ));

        List<SchedulingCandidate> ordered = new ArrayList<>(candidates.size());
        while (!byRun.isEmpty()) {
            List<Map.Entry<UUID, Deque<SchedulingCandidate>>> runOrder = byRun.entrySet().stream()
                    .sorted((left, right) -> compareHeads(left.getValue().peek(), right.getValue().peek(), now))
                    .toList();
            for (Map.Entry<UUID, Deque<SchedulingCandidate>> entry : runOrder) {
                ordered.add(entry.getValue().removeFirst());
                if (entry.getValue().isEmpty()) {
                    byRun.remove(entry.getKey());
                }
            }
        }
        return List.copyOf(ordered);
    }

    public int effectivePriority(SchedulingCandidate candidate, Instant now) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(now, "now must not be null");
        long waitedSeconds = Math.max(0L, Duration.between(candidate.queuedAt(), now).getSeconds());
        long levels = waitedSeconds / agingInterval.getSeconds();
        int boost = (int) Math.min(MAX_PRIORITY - candidate.runPriority(), levels);
        return candidate.runPriority() + boost;
    }

    private int compareHeads(
            SchedulingCandidate left,
            SchedulingCandidate right,
            Instant now
    ) {
        int priority = Integer.compare(effectivePriority(right, now), effectivePriority(left, now));
        if (priority != 0) {
            return priority;
        }
        int waitOrder = left.queuedAt().compareTo(right.queuedAt());
        if (waitOrder != 0) {
            return waitOrder;
        }
        int runOrder = left.runId().toString().compareTo(right.runId().toString());
        if (runOrder != 0) {
            return runOrder;
        }
        int sequenceOrder = Integer.compare(left.sequenceNo(), right.sequenceNo());
        return sequenceOrder != 0
                ? sequenceOrder
                : left.taskId().toString().compareTo(right.taskId().toString());
    }
}
