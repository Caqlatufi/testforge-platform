package mcpserver

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/caqlatufi/testforge-mcp/internal/application"
	"github.com/caqlatufi/testforge-mcp/internal/config"
	"github.com/caqlatufi/testforge-mcp/internal/result"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

func New(service *application.Service, build config.BuildInfo) *mcp.Server {
	server := mcp.NewServer(&mcp.Implementation{
		Name:    "testforge-mcp",
		Version: build.Version,
		Title:   "TestForge MCP",
	}, nil)
	registerTools(server, service)
	registerResources(server, service)
	registerPrompts(server)
	return server
}

func registerTools(server *mcp.Server, service *application.Service) {
	addTool(server, "testforge_inspect_workspace", "Inspect the authorized local Git workspace and show matching or bound TestForge projects without changing state.", true, false, service.InspectWorkspace)
	addTool(server, "testforge_bind_project", "Bind the authorized workspace to an existing TestForge Project, or explicitly create and bind a Project.", false, true, service.BindProject)
	addTool(server, "testforge_validate_case", "Validate a complete YAML-first TestForge Case without saving it.", true, true, service.ValidateCase)
	addTool(server, "testforge_apply_case", "Create or update one YAML-first Case. An optional workspace script is uploaded and bound only to this Case.", false, false, service.ApplyCase)
	addTool(server, "testforge_apply_workflow", "Create or update a project Workflow DAG and optionally publish an immutable Workflow version.", false, false, service.ApplyWorkflow)
	addTool(server, "testforge_prepare_job", "Create a Test Job fixed to a published Workflow and resolvable Git revision, then activate it by default.", false, false, service.PrepareJob)
	addTool(server, "testforge_execute_job", "Execute an active Test Job with a stable idempotency requestKey and return immediately.", false, true, service.ExecuteJob)
	addTool(server, "testforge_get_execution", "Read Test Job attempts and optional Run graph/state without changing TestForge.", true, true, service.GetExecution)
	addTool(server, "testforge_get_report", "Read the deterministic TestForge report, Evidence metadata and AI diagnosis availability for a Run.", true, true, service.GetReport)
}

func addTool[Input any](server *mcp.Server, name, description string, readOnly, idempotent bool, handler func(context.Context, Input) result.OperationResult) {
	destructive := !readOnly
	openWorld := true
	mcp.AddTool(server, &mcp.Tool{
		Name:        name,
		Title:       strings.ReplaceAll(strings.TrimPrefix(name, "testforge_"), "_", " "),
		Description: description,
		Annotations: &mcp.ToolAnnotations{
			ReadOnlyHint:    readOnly,
			IdempotentHint:  idempotent,
			DestructiveHint: &destructive,
			OpenWorldHint:   &openWorld,
		},
	}, func(ctx context.Context, _ *mcp.CallToolRequest, input Input) (*mcp.CallToolResult, result.OperationResult, error) {
		output := handler(ctx, input)
		if output.OK {
			return nil, output, nil
		}
		return &mcp.CallToolResult{IsError: true}, output, nil
	})
}

func registerResources(server *mcp.Server, service *application.Service) {
	server.AddResource(&mcp.Resource{
		URI: "testforge://workspace/context", Name: "workspace-context", Title: "Authorized workspace context",
		Description: "Current Git revision, dirty state, discovered manifests and TestForge binding.", MIMEType: "application/json",
	}, jsonResource(func(ctx context.Context, _ string) result.OperationResult {
		return service.WorkspaceContext(ctx)
	}))
	server.AddResource(&mcp.Resource{
		URI: "testforge://schemas/test-case", Name: "test-case-schema", Title: "YAML-first Case schema guide",
		Description: "Minimal editable YAML-first Case contract used by TestForge.", MIMEType: "text/yaml",
	}, func(_ context.Context, request *mcp.ReadResourceRequest) (*mcp.ReadResourceResult, error) {
		return &mcp.ReadResourceResult{Contents: []*mcp.ResourceContents{{
			URI: request.Params.URI, MIMEType: "text/yaml", Text: caseSchemaGuide,
		}}}, nil
	})
	server.AddResourceTemplate(&mcp.ResourceTemplate{
		URITemplate: "testforge://projects/{projectId}/context", Name: "project-context", Title: "TestForge project context",
		Description: "Project, Case, managed Asset, Workflow and Test Job context.", MIMEType: "application/json",
	}, jsonResource(func(ctx context.Context, uri string) result.OperationResult {
		projectID := between(uri, "testforge://projects/", "/context")
		return service.ProjectContext(ctx, projectID)
	}))
	server.AddResourceTemplate(&mcp.ResourceTemplate{
		URITemplate: "testforge://runs/{runId}/report", Name: "run-report", Title: "TestForge Run report",
		Description: "Deterministic Run report, Evidence metadata and diagnosis availability.", MIMEType: "application/json",
	}, jsonResource(func(ctx context.Context, uri string) result.OperationResult {
		runID := between(uri, "testforge://runs/", "/report")
		return service.GetReport(ctx, application.GetReportInput{RunID: runID})
	}))
}

func jsonResource(load func(context.Context, string) result.OperationResult) mcp.ResourceHandler {
	return func(ctx context.Context, request *mcp.ReadResourceRequest) (*mcp.ReadResourceResult, error) {
		output := load(ctx, request.Params.URI)
		encoded, err := json.MarshalIndent(output, "", "  ")
		if err != nil {
			return nil, fmt.Errorf("encode resource: %w", err)
		}
		return &mcp.ReadResourceResult{Contents: []*mcp.ResourceContents{{
			URI: request.Params.URI, MIMEType: "application/json", Text: string(encoded),
		}}}, nil
	}
}

func between(value, prefix, suffix string) string {
	value = strings.TrimPrefix(value, prefix)
	value = strings.TrimSuffix(value, suffix)
	if strings.Contains(value, "/") {
		return ""
	}
	return value
}

const caseSchemaGuide = `apiVersion: testforge.io/v1alpha1
kind: TestCase
metadata:
  name: Example API assertion
  tags: [smoke]
spec:
  type: assertion
  timeoutSeconds: 30
  execution:
    executor: pytest-http
    interaction: HEADLESS
    capabilities: []
    resourceProfile: script-small
    leaseScope: CASE
  parameters:
    type: object
    additionalProperties: true
  script:
    type: inline
    language: python
    content: |
      def test_example():
          assert True
`
