package io.testforge.casecatalog.workflow.ctrl;

import io.testforge.casecatalog.ctrl.ApiResponse;
import io.testforge.casecatalog.workflow.draft.WorkflowDraft;
import io.testforge.casecatalog.workflow.draft.WorkflowDraftEdge;
import io.testforge.casecatalog.workflow.draft.WorkflowDraftNode;
import io.testforge.casecatalog.workflow.model.CreateWorkflowCommand;
import io.testforge.casecatalog.workflow.model.WorkflowView;
import io.testforge.casecatalog.workflow.service.TestWorkflowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class TestWorkflowController {

    private final TestWorkflowService service;

    public TestWorkflowController(TestWorkflowService service) {
        this.service = service;
    }

    @PostMapping("/projects/{projectId}/workflows")
    public ResponseEntity<ApiResponse<WorkflowView>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateWorkflowRequest request
    ) {
        WorkflowView workflow = service.create(
                projectId,
                new CreateWorkflowCommand(request.targetId(), request.name())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(workflow));
    }

    @GetMapping("/projects/{projectId}/workflows")
    public ApiResponse<List<WorkflowView>> list(@PathVariable UUID projectId) {
        return ApiResponse.success(service.list(projectId));
    }

    @PutMapping("/workflows/{workflowId}/graph")
    public ApiResponse<WorkflowView> saveGraph(
            @PathVariable UUID workflowId,
            @Valid @RequestBody SaveWorkflowGraphRequest request
    ) {
        WorkflowDraft draft = new WorkflowDraft(
                workflowId,
                request.nodes().stream()
                        .map(node -> new WorkflowDraftNode(
                                node.id(),
                                node.type(),
                                node.referenceId(),
                                node.referenceVersion(),
                                node.required(),
                                node.timeoutSeconds(),
                                node.parameterOverrides() == null ? Map.of() : node.parameterOverrides(),
                                node.positionX(),
                                node.positionY()
                        ))
                        .toList(),
                request.edges().stream()
                        .map(edge -> new WorkflowDraftEdge(
                                edge.predecessorNodeId(),
                                edge.successorNodeId(),
                                edge.condition()
                        ))
                        .toList()
        );
        return ApiResponse.success(service.saveGraph(workflowId, request.expectedVersion(), draft));
    }

    @PostMapping("/workflows/{workflowId}/publish")
    public ApiResponse<io.testforge.casecatalog.workflow.compile.model.PublishedWorkflowVersionView> publish(
            @PathVariable UUID workflowId,
            @Valid @RequestBody PublishWorkflowRequest request
    ) {
        return ApiResponse.success(service.publish(workflowId, request.requestKey()));
    }

    @GetMapping("/workflows/{workflowId}")
    public ApiResponse<?> get(
            @PathVariable UUID workflowId,
            @RequestParam(required = false) Integer version
    ) {
        if (version == null) {
            return ApiResponse.success(service.get(workflowId));
        }
        return ApiResponse.success(service.getPublished(workflowId, version));
    }
}
