package application

import (
	"context"
	"net/url"
	"path/filepath"
	"testing"

	"github.com/caqlatufi/testforge-mcp/internal/binding"
	"github.com/caqlatufi/testforge-mcp/internal/workspace"
)

func TestValidateWorkflowGraphRejectsCycle(t *testing.T) {
	graph := map[string]any{
		"expectedVersion": float64(0),
		"nodes": []any{
			map[string]any{"id": "a"},
			map[string]any{"id": "b"},
		},
		"edges": []any{
			map[string]any{"predecessorNodeId": "a", "successorNodeId": "b"},
			map[string]any{"predecessorNodeId": "b", "successorNodeId": "a"},
		},
	}
	if err := validateWorkflowGraph(graph); err == nil {
		t.Fatal("expected cycle validation error")
	}
}

func TestValidateCaseRequiresOneScript(t *testing.T) {
	withoutScript := `apiVersion: testforge.io/v1alpha1
kind: TestCase
metadata:
  name: missing script
spec:
  type: assertion
`
	if err := validateCaseYAML(withoutScript, false); err == nil {
		t.Fatal("expected missing script error")
	}
	if err := validateCaseYAML(withoutScript, true); err != nil {
		t.Fatalf("scriptPath should allow script injection: %v", err)
	}
}

func TestRequestKeyGenerationAndValidation(t *testing.T) {
	generated, err := requestKeyOrNew("")
	if err != nil || len(generated) != 36 {
		t.Fatalf("unexpected generated key %q %v", generated, err)
	}
	provided := "550e8400-e29b-41d4-a716-446655440000"
	if value, err := requestKeyOrNew(provided); err != nil || value != provided {
		t.Fatalf("unexpected provided key %q %v", value, err)
	}
	if _, err := requestKeyOrNew("not-a-uuid"); err == nil {
		t.Fatal("expected invalid UUID error")
	}
}

func TestNormalizeRepositoryTreatsHTTPSAndSSHAsSameIdentity(t *testing.T) {
	https := normalizeRepository("https://github.com/example/project.git")
	ssh := normalizeRepository("git@github.com:example/project.git")
	sshURL := normalizeRepository("ssh://git@github.com/example/project.git")
	if https != ssh || https != sshURL {
		t.Fatalf("repository identities differ: https=%q scp=%q ssh=%q", https, ssh, sshURL)
	}
}

func TestServiceAssetWorkflowJobExecutionAndReportFlow(t *testing.T) {
	root := t.TempDir()
	inspector, err := workspace.NewInspector(root)
	if err != nil {
		t.Fatal(err)
	}
	api := &recordingAPI{}
	service := New("http://testforge.local", api, inspector, binding.NewStore(filepath.Join(t.TempDir(), "bindings.json")))
	ctx := context.Background()
	caseYAML := `apiVersion: testforge.io/v1alpha1
kind: TestCase
metadata:
  name: API smoke
spec:
  type: assertion
  script:
    type: inline
    language: python
    content: |
      def test_ok():
          assert True
`
	caseResult := service.ApplyCase(ctx, ApplyCaseInput{ProjectID: "project-1", YAML: caseYAML})
	if !caseResult.OK {
		t.Fatalf("apply case failed: %+v", caseResult)
	}
	workflowResult := service.ApplyWorkflow(ctx, ApplyWorkflowInput{
		ProjectID: "project-1", WorkflowID: "workflow-1", Publish: true,
		RequestKey: "550e8400-e29b-41d4-a716-446655440000",
		Graph: map[string]any{
			"expectedVersion": float64(0),
			"nodes":           []any{map[string]any{"id": "node-1"}},
			"edges":           []any{},
		},
	})
	if !workflowResult.OK || workflowResult.Status != "PUBLISHED" {
		t.Fatalf("apply workflow failed: %+v", workflowResult)
	}
	jobResult := service.PrepareJob(ctx, PrepareJobInput{
		ProjectID: "project-1", Name: "smoke", WorkflowID: "workflow-1",
		RevisionType: "COMMIT", RevisionValue: "0123456789abcdef0123456789abcdef01234567",
		Platform: "WINDOWS",
	})
	if !jobResult.OK || jobResult.TestJobID != "job-1" || jobResult.Status != "ACTIVE" {
		t.Fatalf("prepare job failed: %+v", jobResult)
	}
	executeResult := service.ExecuteJob(ctx, ExecuteJobInput{TestJobID: "job-1", RequestKey: "550e8400-e29b-41d4-a716-446655440001"})
	if !executeResult.OK || executeResult.RunID != "run-1" {
		t.Fatalf("execute failed: %+v", executeResult)
	}
	executionResult := service.GetExecution(ctx, GetExecutionInput{TestJobID: "job-1", RunID: "run-1"})
	if !executionResult.OK || executionResult.Status != "SUCCEEDED" {
		t.Fatalf("get execution failed: %+v", executionResult)
	}
	reportResult := service.GetReport(ctx, GetReportInput{RunID: "run-1"})
	if !reportResult.OK || reportResult.Status != "SUCCEEDED" {
		t.Fatalf("get report failed: %+v", reportResult)
	}
	for _, expected := range []string{
		"POST /api/v1/projects/project-1/case-definitions/validate",
		"POST /api/v1/projects/project-1/case-definitions",
		"PUT /api/v1/workflows/workflow-1/graph",
		"POST /api/v1/workflows/workflow-1/publish",
		"POST /api/v1/test-jobs",
		"POST /api/v1/test-jobs/job-1/activate",
		"POST /api/v1/test-jobs/job-1/execute",
		"GET /api/v1/reports/runs/run-1",
	} {
		if !contains(api.calls, expected) {
			t.Errorf("missing API call %q in %#v", expected, api.calls)
		}
	}
}

type recordingAPI struct {
	calls []string
}

func (api *recordingAPI) Request(_ context.Context, method, path string, _ url.Values, _ any) (any, error) {
	api.calls = append(api.calls, method+" "+path)
	switch method + " " + path {
	case "POST /api/v1/projects/project-1/case-definitions/validate":
		return map[string]any{"valid": true}, nil
	case "POST /api/v1/projects/project-1/case-definitions":
		return map[string]any{"caseId": "case-1", "valid": true}, nil
	case "PUT /api/v1/workflows/workflow-1/graph":
		return map[string]any{"id": "workflow-1", "draftRevision": 1}, nil
	case "POST /api/v1/workflows/workflow-1/publish":
		return map[string]any{"id": "workflow-1", "version": 1}, nil
	case "POST /api/v1/test-jobs":
		return map[string]any{"id": "job-1", "configVersion": float64(1), "state": "DRAFT"}, nil
	case "POST /api/v1/test-jobs/job-1/activate":
		return map[string]any{"id": "job-1", "configVersion": float64(1), "state": "ACTIVE"}, nil
	case "POST /api/v1/test-jobs/job-1/execute":
		return map[string]any{"runId": "run-1", "state": "QUEUED"}, nil
	case "GET /api/v1/test-jobs/job-1":
		return map[string]any{"id": "job-1", "state": "ACTIVE"}, nil
	case "GET /api/v1/test-jobs/job-1/attempts":
		return []any{map[string]any{"id": "run-1", "state": "SUCCEEDED"}}, nil
	case "GET /api/v1/runs/run-1":
		return map[string]any{"id": "run-1", "state": "SUCCEEDED"}, nil
	case "GET /api/v1/runs/run-1/graph":
		return map[string]any{"nodes": []any{}, "edges": []any{}}, nil
	case "GET /api/v1/reports/runs/run-1":
		return map[string]any{"runId": "run-1", "status": "SUCCEEDED"}, nil
	case "GET /api/v1/reports/run-1/diagnosis/status":
		return map[string]any{"available": false}, nil
	default:
		return map[string]any{}, nil
	}
}

func (api *recordingAPI) Upload(_ context.Context, _, _ string, _ []byte) (any, error) {
	return map[string]any{"id": "asset-1"}, nil
}

func contains(values []string, expected string) bool {
	for _, value := range values {
		if value == expected {
			return true
		}
	}
	return false
}
