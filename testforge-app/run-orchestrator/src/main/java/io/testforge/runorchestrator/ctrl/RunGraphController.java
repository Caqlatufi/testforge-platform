package io.testforge.runorchestrator.ctrl;

import io.testforge.casecatalog.workflow.compile.service.WorkflowPublishService;
import io.testforge.casecatalog.workflow.service.TestWorkflowService;
import io.testforge.casecatalog.testcase.service.TestCaseService;
import io.testforge.casecatalog.suite.service.TestSuiteService;
import io.testforge.runorchestrator.run.service.RunTaskService;
import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.model.TaskView;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/runs")
public class RunGraphController {
    private final RunTaskService runs;
    private final WorkflowPublishService workflows;
    private final TestCaseService cases;
    private final TestSuiteService suites;
    private final TestWorkflowService workflowCatalog;

    public RunGraphController(RunTaskService runs, WorkflowPublishService workflows,
                              TestCaseService cases, TestSuiteService suites,
                              TestWorkflowService workflowCatalog) {
        this.runs = runs; this.workflows = workflows; this.cases = cases;
        this.suites = suites; this.workflowCatalog = workflowCatalog;
    }

    @GetMapping("/{runId}/graph")
    public ApiResponse<RunGraph> graph(@PathVariable UUID runId) {
        var run = runs.getRun(runId);
        var published = workflows.get(run.workflowId(), run.workflowVersion());
        if (published.displaySnapshot() == null) return ApiResponse.success(compiledFallback(run));
        var executionSnapshot = runs.getExecutionSnapshot(runId);
        Map<UUID, io.testforge.casecatalog.workflow.compile.model.CompiledNode> executionNodes =
                executionSnapshot == null ? Map.of() : executionSnapshot.nodes().stream()
                        .collect(Collectors.toMap(
                                io.testforge.casecatalog.workflow.compile.model.CompiledNode::id,
                                Function.identity()
                        ));
        Map<UUID, TaskView> tasksByNode = run.tasks().stream()
                .collect(Collectors.toMap(TaskView::workflowNodeId, Function.identity()));
        var display = published.displaySnapshot();
        List<Node> nodes = display.graph().nodes().stream().map(node -> {
            List<TaskView> mapped = display.nodeMapping().getOrDefault(node.id(), List.of()).stream()
                    .map(tasksByNode::get).filter(java.util.Objects::nonNull).toList();
            return new Node(node.id(), node.type().name(), displayLabel(node, mapped, executionNodes),
                    node.positionX(), node.positionY(), aggregate(mapped),
                    mapped.stream().map(TaskView::id).toList(), mapped.size(),
                    mapped.stream().filter(task -> task.state().isTerminal()).count());
        }).toList();
        List<Edge> edges = display.graph().edges().stream()
                .map(edge -> new Edge(edge.predecessorNodeId(), edge.successorNodeId(), edge.condition().name()))
                .toList();
        return ApiResponse.success(new RunGraph(run.id(), run.testJobId(), run.workflowId(),
                run.workflowVersion(), false, nodes, edges));
    }

    private String displayLabel(
            io.testforge.casecatalog.workflow.draft.WorkflowDraftNode node,
            List<TaskView> mappedTasks,
            Map<UUID, io.testforge.casecatalog.workflow.compile.model.CompiledNode> executionNodes
    ) {
        if (node.type() == io.testforge.casecatalog.workflow.draft.WorkflowNodeType.CASE
                || node.type() == io.testforge.casecatalog.workflow.draft.WorkflowNodeType.FIXTURE) {
            String frozenName = mappedTasks.stream()
                    .map(TaskView::workflowNodeId)
                    .map(executionNodes::get)
                    .filter(java.util.Objects::nonNull)
                    .map(io.testforge.casecatalog.workflow.compile.model.CompiledNode::displayName)
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse(null);
            if (frozenName != null) return frozenName;
        }
        try {
            return switch (node.type()) {
                case CASE, FIXTURE -> cases.getTestCase(node.referenceId()).name();
                case SUITE -> suites.get(node.referenceId()).name();
                case SUBFLOW -> workflowCatalog.get(node.referenceId()).name();
            };
        } catch (RuntimeException ignored) {
            return node.type().name() + " · " + shortId(node.referenceId());
        }
    }

    private RunGraph compiledFallback(io.testforge.runorchestrator.run.model.RunView run) {
        List<Node> nodes = run.tasks().stream().map(task -> new Node(task.workflowNodeId(),
                task.sourceType().name(), task.sourceRef(), 0, task.sequenceNo() * 100.0,
                task.state().name(), List.of(task.id()), 1, task.state().isTerminal() ? 1 : 0)).toList();
        Map<UUID, UUID> displayByTask = run.tasks().stream()
                .collect(Collectors.toMap(TaskView::id, TaskView::workflowNodeId));
        List<Edge> edges = run.tasks().stream().flatMap(task -> task.dependencies().stream())
                .map(edge -> new Edge(displayByTask.get(edge.predecessorTaskId()),
                        displayByTask.get(edge.successorTaskId()), edge.condition().name())).toList();
        return new RunGraph(run.id(), run.testJobId(), run.workflowId(), run.workflowVersion(), true, nodes, edges);
    }

    static String aggregate(List<TaskView> tasks) {
        if (tasks.isEmpty()) return "EMPTY";
        if (tasks.stream().anyMatch(task -> task.state() == TaskState.RUNNING || task.state() == TaskState.DISPATCHED)) return "RUNNING";
        if (tasks.stream().anyMatch(task -> task.state() == TaskState.WAITING_DEPLOYMENT)) return "WAITING_DEPLOYMENT";
        if (tasks.stream().anyMatch(task -> task.state() == TaskState.WAITING_DEPENDENCY)) return "WAITING_DEPENDENCY";
        if (tasks.stream().anyMatch(task -> task.state() == TaskState.QUEUED)) return "QUEUED";
        if (tasks.stream().anyMatch(task -> task.required() && (task.state() == TaskState.FAILED || task.state() == TaskState.TIMEOUT))) return "FAILED";
        if (tasks.stream().anyMatch(task -> task.state() == TaskState.BLOCKED)) return "BLOCKED";
        if (tasks.stream().anyMatch(task -> task.state() == TaskState.CANCELLED)) return "CANCELLED";
        if (tasks.stream().anyMatch(task -> task.state() == TaskState.FAILED || task.state() == TaskState.TIMEOUT)) return "COMPLETED_WITH_WARNINGS";
        if (tasks.stream().allMatch(task -> task.state() == TaskState.SUCCEEDED)) return "SUCCEEDED";
        return "FAILED";
    }

    private String shortId(UUID id) { return id == null ? "未绑定" : id.toString().substring(0, 8); }

    public record RunGraph(UUID runId, UUID testJobId, UUID workflowId, int workflowVersion,
                           boolean compiledFallback, List<Node> nodes, List<Edge> edges) { }
    public record Node(UUID id, String type, String label, double positionX, double positionY,
                       String state, List<UUID> taskIds, int totalTasks, long completedTasks) { }
    public record Edge(UUID sourceNodeId, UUID targetNodeId, String condition) { }
}
