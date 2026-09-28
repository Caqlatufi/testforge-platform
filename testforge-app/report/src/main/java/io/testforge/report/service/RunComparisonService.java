package io.testforge.report.service;

import io.testforge.runorchestrator.run.model.RunView;
import io.testforge.runorchestrator.run.service.RunTaskService;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.model.TaskTargetRevision;
import io.testforge.runorchestrator.task.model.TaskView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class RunComparisonService {
    private final RunTaskService runs;

    public RunComparisonService(RunTaskService runs) {
        this.runs = Objects.requireNonNull(runs);
    }

    public RunComparisonReport compare(UUID baselineRunId, UUID candidateRunId) {
        if (baselineRunId.equals(candidateRunId)) {
            throw new IllegalArgumentException("基准 Run 与候选 Run 不能相同");
        }
        RunView baseline = runs.getRun(baselineRunId);
        RunView candidate = runs.getRun(candidateRunId);
        if (!baseline.projectId().equals(candidate.projectId()) || !baseline.workflowId().equals(candidate.workflowId())) {
            throw new IllegalArgumentException("差分 Run 必须属于同一项目和 Workflow");
        }
        Map<Key, TaskView> left = index(baseline.tasks());
        Map<Key, TaskView> right = index(candidate.tasks());
        List<Key> keys = new ArrayList<>(left.keySet());
        right.keySet().stream().filter(key -> !left.containsKey(key)).forEach(keys::add);
        List<RunComparisonReport.CaseComparison> cases = keys.stream().map(key -> compare(key, left.get(key), right.get(key))).toList();
        return new RunComparisonReport(
                baselineRunId, candidateRunId, commonRevision(baseline.tasks()), commonRevision(candidate.tasks()),
                new RunComparisonReport.Summary(
                        count(cases, "REGRESSION"), count(cases, "FIXED"), count(cases, "BOTH_PASS"),
                        count(cases, "BOTH_FAIL"), count(cases, "NOT_COMPARABLE")
                ), cases
        );
    }

    private Map<Key, TaskView> index(List<TaskView> tasks) {
        Map<UUID, Integer> occurrences = new LinkedHashMap<>();
        Map<Key, TaskView> result = new LinkedHashMap<>();
        for (TaskView task : tasks) {
            int occurrence = occurrences.merge(task.caseId(), 1, Integer::sum);
            result.put(new Key(task.caseId(), occurrence), task);
        }
        return result;
    }

    private RunComparisonReport.CaseComparison compare(Key key, TaskView left, TaskView right) {
        String baseline = outcome(left);
        String candidate = outcome(right);
        String conclusion = switch (baseline + ":" + candidate) {
            case "PASS:PASS" -> "BOTH_PASS";
            case "FAIL:FAIL" -> "BOTH_FAIL";
            case "PASS:FAIL" -> "REGRESSION";
            case "FAIL:PASS" -> "FIXED";
            default -> "NOT_COMPARABLE";
        };
        return new RunComparisonReport.CaseComparison(
                key.caseId(), key.occurrence(), left == null ? null : left.id(), right == null ? null : right.id(),
                baseline, candidate, conclusion
        );
    }

    private String outcome(TaskView task) {
        if (task == null || !task.state().isTerminal()) return "UNAVAILABLE";
        return task.state() == TaskState.SUCCEEDED ? "PASS" :
                task.state() == TaskState.CANCELLED || task.state() == TaskState.BLOCKED ? "UNAVAILABLE" : "FAIL";
    }

    private TaskTargetRevision commonRevision(List<TaskView> tasks) {
        if (tasks.isEmpty()) return null;
        TaskTargetRevision revision = tasks.getFirst().targetRevision();
        return tasks.stream().allMatch(task -> Objects.equals(revision, task.targetRevision())) ? revision : null;
    }

    private long count(List<RunComparisonReport.CaseComparison> cases, String conclusion) {
        return cases.stream().filter(item -> item.conclusion().equals(conclusion)).count();
    }

    private record Key(UUID caseId, int occurrence) { }
}
