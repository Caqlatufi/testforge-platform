package io.testforge.report.ctrl;

import io.testforge.report.service.RunReport;
import io.testforge.report.service.RunReportService;
import io.testforge.report.service.CaseHistory;
import io.testforge.report.service.CaseHistoryService;
import io.testforge.report.service.RunComparisonReport;
import io.testforge.report.service.RunComparisonService;
import io.testforge.runorchestrator.comparison.service.ComparisonRunService;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
public class RunReportController {
    private final RunReportService service;
    private final CaseHistoryService historyService;
    private final RunComparisonService comparisonService;
    private final ComparisonRunService comparisonRuns;

    public RunReportController(RunReportService service, CaseHistoryService historyService,
                               RunComparisonService comparisonService, ComparisonRunService comparisonRuns) {
        this.service = service;
        this.historyService = historyService;
        this.comparisonService = comparisonService;
        this.comparisonRuns = comparisonRuns;
    }

    @GetMapping({"/{runId}", "/runs/{runId}"})
    public Map<String, RunReport> get(@PathVariable UUID runId) {
        return Map.of("data", service.get(runId));
    }

    @GetMapping("/runs/cases/{caseId}/history")
    public Map<String, CaseHistory> history(@PathVariable UUID caseId) {
        return Map.of("data", historyService.get(caseId));
    }

    @GetMapping("/comparisons")
    public Map<String, RunComparisonReport> compare(
            @RequestParam UUID baselineRunId,
            @RequestParam UUID candidateRunId
    ) {
        return Map.of("data", comparisonService.compare(baselineRunId, candidateRunId));
    }

    @GetMapping("/comparison-groups/{comparisonGroupId}")
    public Map<String, RunComparisonReport> compareGroup(@PathVariable UUID comparisonGroupId) {
        var group = comparisonRuns.get(comparisonGroupId);
        return Map.of("data", comparisonService.compare(group.baselineRun().id(), group.candidateRun().id()));
    }
}
