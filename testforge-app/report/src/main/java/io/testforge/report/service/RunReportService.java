package io.testforge.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.report.model.TestResultCommand;
import io.testforge.report.persistence.TestResultEntity;
import io.testforge.report.persistence.TestResultRepository;
import io.testforge.report.port.inbound.TestResultCommandPort;
import io.testforge.runorchestrator.run.service.RunTaskService;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class RunReportService implements TestResultCommandPort {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final TestResultRepository repository;
    private final RunTaskService runTaskService;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();

    public RunReportService(TestResultRepository repository, RunTaskService runTaskService,
                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.runTaskService = runTaskService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void record(TestResultCommand command) {
        UUID attemptId = command.attemptId().orElseThrow();
        var context = runTaskService.getTaskHistoryContext(command.taskId());
        String artifacts = write(command.artifactKeys());
        var existing = repository.findById(command.taskId());
        TestResultEntity entity = existing.orElseGet(() -> new TestResultEntity(
                command.taskId(), context.runId(), context.caseId(), attemptId,
                command.status(), command.durationMs(),
                command.failureType().orElse(null), command.summary(), artifacts, clock.instant()
        ));
        existing.ifPresent(ignored -> entity.replace(
                context.runId(), context.caseId(), attemptId, command.status(), command.durationMs(),
                command.failureType().orElse(null),
                command.summary(), artifacts, clock.instant()
        ));
        repository.save(entity);
    }

    @Transactional(readOnly = true)
    public RunReport get(UUID runId) {
        var run = runTaskService.getRun(runId);
        Map<UUID, io.testforge.runorchestrator.task.model.TaskView> tasksById = run.tasks().stream()
                .collect(Collectors.toMap(task -> task.id(), task -> task));
        List<TestResultEntity> entities = repository.findAllByTaskIdIn(
                run.tasks().stream().map(task -> task.id()).toList()
        );
        List<RunReport.ResultItem> items = entities.stream()
                .sorted(Comparator.comparing(TestResultEntity::getRecordedAt))
                .map(entity -> new RunReport.ResultItem(
                        entity.getTaskId(), entity.getCaseId(), entity.getAttemptId(), entity.getStatus(), entity.getDurationMs(),
                        entity.getFailureType(), entity.getSummary(), read(entity.getArtifactKeysJson()),
                        tasksById.get(entity.getTaskId()).targetRevision()
                )).toList();
        int passed = (int) items.stream().filter(item -> "PASSED".equals(item.status())).count();
        int failed = items.size() - passed;
        List<Long> durations = items.stream().map(RunReport.ResultItem::durationMs).sorted().toList();
        long p50 = percentile(durations, .50);
        long p95 = durations.isEmpty() ? 0 : durations.get(Math.max(0, (int) Math.ceil(durations.size() * .95) - 1));
        Map<String, Long> categories = items.stream().filter(item -> item.failureType() != null)
                .collect(Collectors.groupingBy(RunReport.ResultItem::failureType, LinkedHashMap::new, Collectors.counting()));
        List<String> artifacts = items.stream().flatMap(item -> item.artifactKeys().stream()).distinct().toList();
        return new RunReport(runId, run.state().name(), run.tasks().size(), passed, failed,
                run.tasks().size() - items.size(), durations.stream().mapToLong(Long::longValue).sum(),
                p50, p95, categories, items, artifacts);
    }

    private long percentile(List<Long> sorted, double quantile) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * quantile) - 1));
    }

    private String write(List<String> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("附件索引无法序列化", exception); }
    }

    private List<String> read(String value) {
        try { return objectMapper.readValue(value, STRING_LIST); }
        catch (JsonProcessingException exception) { return new ArrayList<>(); }
    }
}
