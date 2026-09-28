package io.testforge.report.service;

import io.testforge.runorchestrator.task.model.TaskTargetRevision;

import java.util.List;
import java.util.UUID;

public record RunComparisonReport(
        UUID baselineRunId,
        UUID candidateRunId,
        TaskTargetRevision baselineRevision,
        TaskTargetRevision candidateRevision,
        Summary summary,
        List<CaseComparison> cases
) {
    public record Summary(long regressions, long fixed, long bothPass, long bothFail, long notComparable) { }

    public record CaseComparison(
            UUID caseId,
            int occurrence,
            UUID baselineTaskId,
            UUID candidateTaskId,
            String baselineOutcome,
            String candidateOutcome,
            String conclusion
    ) { }
}
