package application

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strings"

	"github.com/caqlatufi/testforge-mcp/internal/binding"
	"github.com/caqlatufi/testforge-mcp/internal/result"
	"github.com/caqlatufi/testforge-mcp/internal/testforge"
	"github.com/caqlatufi/testforge-mcp/internal/workspace"
	"go.yaml.in/yaml/v3"
)

type Service struct {
	endpoint  string
	api       testforge.API
	inspector *workspace.Inspector
	bindings  *binding.Store
}

type InspectWorkspaceInput struct {
	Workspace string `json:"workspace,omitempty" jsonschema:"optional path inside the configured workspace root"`
}

type ProjectCreateInput struct {
	Name          string `json:"name" jsonschema:"human-readable project name"`
	DefaultBranch string `json:"defaultBranch,omitempty" jsonschema:"default Git branch; defaults to current branch or master"`
}

type BindProjectInput struct {
	Workspace string              `json:"workspace,omitempty" jsonschema:"optional path inside the configured workspace root"`
	ProjectID string              `json:"projectId,omitempty" jsonschema:"existing TestForge project UUID to bind explicitly"`
	Create    *ProjectCreateInput `json:"createProject,omitempty" jsonschema:"create a project when no existing project should be used"`
}

type ValidateCaseInput struct {
	ProjectID string `json:"projectId,omitempty" jsonschema:"TestForge project UUID; omitted uses local workspace binding"`
	YAML      string `json:"yaml" jsonschema:"complete TestForge YAML-first Case definition"`
}

type ApplyCaseInput struct {
	ProjectID  string `json:"projectId,omitempty" jsonschema:"TestForge project UUID; omitted uses local workspace binding"`
	CaseID     string `json:"caseId,omitempty" jsonschema:"existing Case UUID; omit to create a Case"`
	YAML       string `json:"yaml" jsonschema:"complete TestForge YAML-first Case definition"`
	ScriptPath string `json:"scriptPath,omitempty" jsonschema:"script or single-entry archive path inside the authorized workspace; upload result is inserted into YAML"`
}

type ApplyWorkflowInput struct {
	ProjectID  string         `json:"projectId,omitempty" jsonschema:"TestForge project UUID; omitted uses local workspace binding"`
	WorkflowID string         `json:"workflowId,omitempty" jsonschema:"existing Workflow UUID; omit to create a Workflow"`
	TargetID   string         `json:"targetId,omitempty" jsonschema:"project Target UUID; omitted uses the first project Target"`
	Name       string         `json:"name,omitempty" jsonschema:"required when creating a Workflow"`
	Graph      map[string]any `json:"graph" jsonschema:"Workflow graph with expectedVersion, nodes and edges"`
	Publish    bool           `json:"publish,omitempty" jsonschema:"publish a new immutable Workflow version after saving the graph"`
	RequestKey string         `json:"requestKey,omitempty" jsonschema:"UUID idempotency key for publish; generated when omitted"`
}

type PrepareJobInput struct {
	ProjectID      string `json:"projectId,omitempty" jsonschema:"TestForge project UUID; omitted uses local workspace binding"`
	Name           string `json:"name" jsonschema:"test job display name"`
	Description    string `json:"description,omitempty" jsonschema:"optional test purpose"`
	WorkflowID     string `json:"workflowId" jsonschema:"published Workflow UUID"`
	RevisionType   string `json:"revisionType,omitempty" jsonschema:"HEAD_COMMIT, DEFAULT_BRANCH, BRANCH, TAG or COMMIT; defaults to HEAD_COMMIT"`
	RevisionValue  string `json:"revisionValue,omitempty" jsonschema:"branch, tag or commit value when required"`
	Platform       string `json:"platform" jsonschema:"WINDOWS, ANDROID or IOS"`
	Priority       int    `json:"priority,omitempty" jsonschema:"5 for normal or 9 for urgent; defaults to 5"`
	AllowDirtyHEAD bool   `json:"allowDirtyHead,omitempty" jsonschema:"explicitly test committed HEAD while excluding dirty working tree changes"`
	Activate       *bool  `json:"activate,omitempty" jsonschema:"activate after creation; defaults to true"`
}

type ExecuteJobInput struct {
	TestJobID  string `json:"testJobId" jsonschema:"active Test Job UUID"`
	RequestKey string `json:"requestKey,omitempty" jsonschema:"UUID idempotency key; generated when omitted"`
}

type GetExecutionInput struct {
	TestJobID string `json:"testJobId" jsonschema:"Test Job UUID"`
	RunID     string `json:"runId,omitempty" jsonschema:"optional Run UUID for graph and current state"`
}

type GetReportInput struct {
	RunID string `json:"runId" jsonschema:"terminal or active Run UUID"`
}

func New(endpoint string, api testforge.API, inspector *workspace.Inspector, bindings *binding.Store) *Service {
	return &Service{endpoint: strings.TrimRight(endpoint, "/"), api: api, inspector: inspector, bindings: bindings}
}

func (s *Service) InspectWorkspace(ctx context.Context, input InspectWorkspaceInput) result.OperationResult {
	const operation = "inspect_workspace"
	facts, err := s.inspector.Inspect(ctx, input.Workspace)
	if err != nil {
		return fail(operation, err)
	}
	data := map[string]any{"workspace": facts, "bindingStatus": "UNBOUND", "projectCandidates": []any{}}
	if saved, found, err := s.bindings.Get(s.endpoint, facts.Identity); err != nil {
		return fail(operation, err)
	} else if found {
		data["bindingStatus"] = "BOUND"
		data["binding"] = saved
	}
	projects, err := s.api.Request(ctx, http.MethodGet, "/api/v1/projects", nil, nil)
	output := result.Success(operation, "READY", "workspace inspected", data)
	if err != nil {
		output.Warnings = append(output.Warnings, "Local workspace facts are available, but TestForge project candidates could not be loaded: "+err.Error())
		output.NextActions = append(output.NextActions, "Check TestForge connectivity before binding or writing assets")
		return output
	}
	candidates := matchingProjects(projects, facts.RepositoryURL, facts.Root)
	data["projectCandidates"] = candidates
	if data["bindingStatus"] == "UNBOUND" {
		if len(candidates) == 1 {
			output.NextActions = append(output.NextActions, "Bind the unique project candidate explicitly")
		} else {
			output.NextActions = append(output.NextActions, "Select an existing project or create one, then bind it")
		}
	}
	return output
}

func (s *Service) BindProject(ctx context.Context, input BindProjectInput) result.OperationResult {
	const operation = "bind_project"
	facts, err := s.inspector.Inspect(ctx, input.Workspace)
	if err != nil {
		return fail(operation, err)
	}
	var project any
	switch {
	case strings.TrimSpace(input.ProjectID) != "" && input.Create != nil:
		return result.Failure(operation, "PLATFORM_INPUT", "projectId and createProject are mutually exclusive", false)
	case strings.TrimSpace(input.ProjectID) != "":
		project, err = s.api.Request(ctx, http.MethodGet, "/api/v1/projects/"+url.PathEscape(input.ProjectID), nil, nil)
	case input.Create != nil:
		name := strings.TrimSpace(input.Create.Name)
		if name == "" {
			return result.Failure(operation, "PLATFORM_INPUT", "createProject.name is required", false)
		}
		branch := strings.TrimSpace(input.Create.DefaultBranch)
		if branch == "" {
			branch = facts.Branch
		}
		if branch == "" {
			branch = "master"
		}
		repository := facts.RepositoryURL
		if repository == "" {
			repository = facts.Root
		}
		project, err = s.api.Request(ctx, http.MethodPost, "/api/v1/projects", nil, map[string]any{
			"name": name, "repositoryUrl": repository, "defaultBranch": branch,
		})
	default:
		var projects any
		projects, err = s.api.Request(ctx, http.MethodGet, "/api/v1/projects", nil, nil)
		if err == nil {
			matches := matchingProjects(projects, facts.RepositoryURL, facts.Root)
			switch len(matches) {
			case 0:
				return result.Failure(operation, "PROJECT_NOT_BOUND", "no TestForge project matches this workspace; choose or create one explicitly", false)
			case 1:
				project = matches[0]
			default:
				failure := result.Failure(operation, "PROJECT_AMBIGUOUS", "multiple TestForge projects match this workspace; choose projectId explicitly", false)
				failure.Data = map[string]any{"projectCandidates": matches}
				return failure
			}
		}
	}
	if err != nil {
		return fail(operation, err)
	}
	projectID := stringField(project, "id")
	if projectID == "" {
		return result.Failure(operation, "CONTRACT_MISMATCH", "TestForge project response has no id", false)
	}
	projectRepository := stringField(project, "repositoryUrl")
	if projectRepository != "" && facts.RepositoryURL != "" && normalizeRepository(projectRepository) != normalizeRepository(facts.RepositoryURL) {
		return result.Failure(operation, "PROJECT_NOT_BOUND", "selected project repository does not match the local workspace remote", false)
	}
	value := binding.WorkspaceBinding{
		TestForgeURL: s.endpoint, WorkspaceIdentity: facts.Identity, ProjectID: projectID,
		RepositoryURL: facts.RepositoryURL,
	}
	if err := s.bindings.Save(value); err != nil {
		return fail(operation, err)
	}
	output := result.Success(operation, "BOUND", "workspace bound to TestForge project", map[string]any{"workspace": facts, "project": project, "binding": value})
	output.ProjectID = projectID
	output.NextActions = append(output.NextActions, "Inspect or apply Case and Workflow assets")
	return output
}

func (s *Service) ValidateCase(ctx context.Context, input ValidateCaseInput) result.OperationResult {
	const operation = "validate_case"
	projectID, errResult := s.projectID(ctx, input.ProjectID)
	if errResult != nil {
		return *errResult
	}
	if err := validateCaseYAML(input.YAML, false); err != nil {
		return result.Failure(operation, "ASSET_INVALID", err.Error(), false)
	}
	data, err := s.api.Request(ctx, http.MethodPost, "/api/v1/projects/"+url.PathEscape(projectID)+"/case-definitions/validate", nil, map[string]any{"yaml": input.YAML})
	if err != nil {
		return fail(operation, err)
	}
	output := result.Success(operation, "VALID", "Case YAML passed TestForge validation", data)
	output.ProjectID = projectID
	return output
}

func (s *Service) ApplyCase(ctx context.Context, input ApplyCaseInput) result.OperationResult {
	const operation = "apply_case"
	projectID, errResult := s.projectID(ctx, input.ProjectID)
	if errResult != nil {
		return *errResult
	}
	allowMissingScript := strings.TrimSpace(input.ScriptPath) != ""
	if err := validateCaseYAML(input.YAML, allowMissingScript); err != nil {
		return result.Failure(operation, "ASSET_INVALID", err.Error(), false)
	}
	caseYAML := input.YAML
	var asset any
	if allowMissingScript {
		content, fileName, err := s.inspector.ReadFile(input.ScriptPath)
		if err != nil {
			return fail(operation, err)
		}
		asset, err = s.api.Upload(ctx, "/api/v1/projects/"+url.PathEscape(projectID)+"/assets", fileName, content)
		if err != nil {
			return fail(operation, err)
		}
		caseYAML, err = injectAsset(input.YAML, asset)
		if err != nil {
			return result.Failure(operation, "CONTRACT_MISMATCH", err.Error(), false)
		}
	}
	if _, err := s.api.Request(ctx, http.MethodPost, "/api/v1/projects/"+url.PathEscape(projectID)+"/case-definitions/validate", nil, map[string]any{"yaml": caseYAML}); err != nil {
		return fail(operation, err)
	}
	method := http.MethodPost
	path := "/api/v1/projects/" + url.PathEscape(projectID) + "/case-definitions"
	if strings.TrimSpace(input.CaseID) != "" {
		method = http.MethodPut
		path = "/api/v1/cases/" + url.PathEscape(input.CaseID) + "/definition"
	}
	data, err := s.api.Request(ctx, method, path, nil, map[string]any{"yaml": caseYAML})
	if err != nil {
		return fail(operation, err)
	}
	output := result.Success(operation, "APPLIED", "Case definition applied", map[string]any{"case": data, "asset": asset, "yaml": caseYAML})
	output.ProjectID = projectID
	output.NextActions = append(output.NextActions, "Add the Case to a project Workflow or publish the existing Workflow")
	return output
}

func (s *Service) ApplyWorkflow(ctx context.Context, input ApplyWorkflowInput) result.OperationResult {
	const operation = "apply_workflow"
	projectID, errResult := s.projectID(ctx, input.ProjectID)
	if errResult != nil {
		return *errResult
	}
	if err := validateWorkflowGraph(input.Graph); err != nil {
		return result.Failure(operation, "WORKFLOW_INVALID", err.Error(), false)
	}
	workflowID := strings.TrimSpace(input.WorkflowID)
	var created any
	var err error
	if workflowID == "" {
		if strings.TrimSpace(input.Name) == "" {
			return result.Failure(operation, "PLATFORM_INPUT", "name is required when creating a Workflow", false)
		}
		targetID := strings.TrimSpace(input.TargetID)
		if targetID == "" {
			project, requestErr := s.api.Request(ctx, http.MethodGet, "/api/v1/projects/"+url.PathEscape(projectID), nil, nil)
			if requestErr != nil {
				return fail(operation, requestErr)
			}
			targetID = firstTargetID(project)
		}
		if targetID == "" {
			return result.Failure(operation, "PLATFORM_INPUT", "project has no Target and targetId was not provided", false)
		}
		created, err = s.api.Request(ctx, http.MethodPost, "/api/v1/projects/"+url.PathEscape(projectID)+"/workflows", nil, map[string]any{"targetId": targetID, "name": input.Name})
		if err != nil {
			return fail(operation, err)
		}
		workflowID = stringField(created, "id")
		if workflowID == "" {
			return result.Failure(operation, "CONTRACT_MISMATCH", "created Workflow response has no id", false)
		}
	}
	draft, err := s.api.Request(ctx, http.MethodPut, "/api/v1/workflows/"+url.PathEscape(workflowID)+"/graph", nil, input.Graph)
	if err != nil {
		return fail(operation, err)
	}
	data := map[string]any{"workflowId": workflowID, "created": created, "draft": draft}
	status := "DRAFT"
	requestKey := ""
	if input.Publish {
		requestKey, err = requestKeyOrNew(input.RequestKey)
		if err != nil {
			return result.Failure(operation, "PLATFORM_INPUT", err.Error(), false)
		}
		published, requestErr := s.api.Request(ctx, http.MethodPost, "/api/v1/workflows/"+url.PathEscape(workflowID)+"/publish", nil, map[string]any{"requestKey": requestKey})
		if requestErr != nil {
			return fail(operation, requestErr)
		}
		data["published"] = published
		status = "PUBLISHED"
	}
	output := result.Success(operation, status, "Workflow graph applied", data)
	output.ProjectID = projectID
	output.RequestKey = requestKey
	output.NextActions = append(output.NextActions, "Prepare a Test Job from the published Workflow")
	return output
}

func (s *Service) PrepareJob(ctx context.Context, input PrepareJobInput) result.OperationResult {
	const operation = "prepare_job"
	projectID, errResult := s.projectID(ctx, input.ProjectID)
	if errResult != nil {
		return *errResult
	}
	if strings.TrimSpace(input.Name) == "" || strings.TrimSpace(input.WorkflowID) == "" {
		return result.Failure(operation, "PLATFORM_INPUT", "name and workflowId are required", false)
	}
	platform := strings.ToUpper(strings.TrimSpace(input.Platform))
	if platform != "WINDOWS" && platform != "ANDROID" && platform != "IOS" {
		return result.Failure(operation, "PLATFORM_INPUT", "platform must be WINDOWS, ANDROID or IOS", false)
	}
	priority := input.Priority
	if priority == 0 {
		priority = 5
	}
	if priority != 5 && priority != 9 {
		return result.Failure(operation, "PLATFORM_INPUT", "priority must be 5 or 9", false)
	}
	revisionType := strings.ToUpper(strings.TrimSpace(input.RevisionType))
	if revisionType == "" {
		revisionType = "HEAD_COMMIT"
	}
	revisionValue := strings.TrimSpace(input.RevisionValue)
	warnings := []string{}
	if revisionType == "HEAD_COMMIT" {
		facts, err := s.inspector.Inspect(ctx, "")
		if err != nil {
			return fail(operation, err)
		}
		if facts.Dirty && !input.AllowDirtyHEAD {
			return result.Failure(operation, "DIRTY_WORKTREE", "workspace has uncommitted changes; commit them or explicitly allow testing committed HEAD only", false)
		}
		if facts.Dirty {
			warnings = append(warnings, "Uncommitted workspace changes are excluded; the Test Job uses committed HEAD only")
		}
		revisionType = "COMMIT"
		revisionValue = facts.HeadCommit
	}
	if (revisionType == "BRANCH" || revisionType == "TAG" || revisionType == "COMMIT") && revisionValue == "" {
		return result.Failure(operation, "PLATFORM_INPUT", "revisionValue is required for BRANCH, TAG or COMMIT", false)
	}
	if revisionType == "COMMIT" && !regexp.MustCompile(`^[0-9a-fA-F]{40}$`).MatchString(revisionValue) {
		return result.Failure(operation, "PLATFORM_INPUT", "COMMIT revisionValue must be a 40-character SHA", false)
	}
	if revisionType != "DEFAULT_BRANCH" && revisionType != "BRANCH" && revisionType != "TAG" && revisionType != "COMMIT" {
		return result.Failure(operation, "PLATFORM_INPUT", "unsupported revisionType", false)
	}
	body := map[string]any{
		"projectId": projectID, "name": input.Name, "description": input.Description,
		"workflowId": input.WorkflowID, "revisionType": revisionType, "platform": platform,
		"priority": priority, "configVersion": 0,
	}
	if revisionValue != "" {
		body["revisionValue"] = revisionValue
	}
	job, err := s.api.Request(ctx, http.MethodPost, "/api/v1/test-jobs", nil, body)
	if err != nil {
		return fail(operation, err)
	}
	jobID := stringField(job, "id")
	if jobID == "" {
		return result.Failure(operation, "CONTRACT_MISMATCH", "created Test Job response has no id", false)
	}
	activate := input.Activate == nil || *input.Activate
	if activate {
		version, ok := intField(job, "configVersion")
		if !ok || version < 1 {
			return result.Failure(operation, "CONTRACT_MISMATCH", "created Test Job response has no valid configVersion", false)
		}
		job, err = s.api.Request(ctx, http.MethodPost, "/api/v1/test-jobs/"+url.PathEscape(jobID)+"/activate", nil, map[string]any{"configVersion": version})
		if err != nil {
			return fail(operation, err)
		}
	}
	status := "DRAFT"
	if activate {
		status = "ACTIVE"
	}
	output := result.Success(operation, status, "Test Job prepared with a fixed revision", job)
	output.ProjectID = projectID
	output.TestJobID = jobID
	output.Warnings = append(output.Warnings, warnings...)
	output.NextActions = append(output.NextActions, "Execute the Test Job with a stable requestKey")
	return output
}

func (s *Service) ExecuteJob(ctx context.Context, input ExecuteJobInput) result.OperationResult {
	const operation = "execute_job"
	if strings.TrimSpace(input.TestJobID) == "" {
		return result.Failure(operation, "PLATFORM_INPUT", "testJobId is required", false)
	}
	requestKey, err := requestKeyOrNew(input.RequestKey)
	if err != nil {
		return result.Failure(operation, "PLATFORM_INPUT", err.Error(), false)
	}
	data, err := s.api.Request(ctx, http.MethodPost, "/api/v1/test-jobs/"+url.PathEscape(input.TestJobID)+"/execute", nil, map[string]any{"requestKey": requestKey})
	if err != nil {
		failure := fail(operation, err)
		failure.TestJobID = input.TestJobID
		failure.RequestKey = requestKey
		failure.NextActions = append(failure.NextActions, "Query the Test Job using the same requestKey before retrying an uncertain execution")
		return failure
	}
	output := result.Success(operation, "ACCEPTED", "Test Job execution accepted", data)
	output.TestJobID = input.TestJobID
	output.RequestKey = requestKey
	output.RunID = firstNonEmpty(stringField(data, "runId"), stringField(data, "id"))
	output.NextActions = append(output.NextActions, "Poll execution state instead of holding the Tool call open")
	return output
}

func (s *Service) GetExecution(ctx context.Context, input GetExecutionInput) result.OperationResult {
	const operation = "get_execution"
	if strings.TrimSpace(input.TestJobID) == "" {
		return result.Failure(operation, "PLATFORM_INPUT", "testJobId is required", false)
	}
	job, err := s.api.Request(ctx, http.MethodGet, "/api/v1/test-jobs/"+url.PathEscape(input.TestJobID), nil, nil)
	if err != nil {
		return fail(operation, err)
	}
	attempts, err := s.api.Request(ctx, http.MethodGet, "/api/v1/test-jobs/"+url.PathEscape(input.TestJobID)+"/attempts", nil, nil)
	if err != nil {
		return fail(operation, err)
	}
	data := map[string]any{"testJob": job, "attempts": attempts}
	status := stringField(job, "state")
	if strings.TrimSpace(input.RunID) != "" {
		run, requestErr := s.api.Request(ctx, http.MethodGet, "/api/v1/runs/"+url.PathEscape(input.RunID), nil, nil)
		if requestErr != nil {
			return fail(operation, requestErr)
		}
		graph, requestErr := s.api.Request(ctx, http.MethodGet, "/api/v1/runs/"+url.PathEscape(input.RunID)+"/graph", nil, nil)
		if requestErr != nil {
			return fail(operation, requestErr)
		}
		data["run"] = run
		data["graph"] = graph
		status = firstNonEmpty(stringField(run, "state"), stringField(run, "status"), status)
	}
	output := result.Success(operation, firstNonEmpty(status, "UNKNOWN"), "execution state loaded", data)
	output.TestJobID = input.TestJobID
	output.RunID = input.RunID
	return output
}

func (s *Service) GetReport(ctx context.Context, input GetReportInput) result.OperationResult {
	const operation = "get_report"
	if strings.TrimSpace(input.RunID) == "" {
		return result.Failure(operation, "PLATFORM_INPUT", "runId is required", false)
	}
	report, err := s.api.Request(ctx, http.MethodGet, "/api/v1/reports/runs/"+url.PathEscape(input.RunID), nil, nil)
	if err != nil {
		return fail(operation, err)
	}
	diagnosis, diagnosisErr := s.api.Request(ctx, http.MethodGet, "/api/v1/reports/"+url.PathEscape(input.RunID)+"/diagnosis/status", nil, nil)
	data := map[string]any{"report": report}
	output := result.Success(operation, firstNonEmpty(stringField(report, "state"), stringField(report, "status"), "READY"), "deterministic report loaded", data)
	if diagnosisErr != nil {
		output.Warnings = append(output.Warnings, "Diagnosis status is unavailable: "+diagnosisErr.Error())
	} else {
		data["diagnosis"] = diagnosis
	}
	output.RunID = input.RunID
	return output
}

func (s *Service) ProjectContext(ctx context.Context, projectID string) result.OperationResult {
	const operation = "project_context"
	if strings.TrimSpace(projectID) == "" {
		return result.Failure(operation, "PLATFORM_INPUT", "projectId is required", false)
	}
	requests := []struct {
		name  string
		path  string
		query url.Values
	}{
		{"project", "/api/v1/projects/" + url.PathEscape(projectID), nil},
		{"cases", "/api/v1/projects/" + url.PathEscape(projectID) + "/cases", nil},
		{"assets", "/api/v1/projects/" + url.PathEscape(projectID) + "/assets", nil},
		{"sharedCases", "/api/v1/cases/shared", nil},
		{"workflows", "/api/v1/projects/" + url.PathEscape(projectID) + "/workflows", nil},
		{"testJobs", "/api/v1/test-jobs", url.Values{"projectId": []string{projectID}}},
	}
	data := map[string]any{}
	for _, request := range requests {
		value, err := s.api.Request(ctx, http.MethodGet, request.path, request.query, nil)
		if err != nil {
			return fail(operation, err)
		}
		data[request.name] = value
	}
	output := result.Success(operation, "READY", "project context loaded", data)
	output.ProjectID = projectID
	return output
}

func (s *Service) WorkspaceContext(ctx context.Context) result.OperationResult {
	return s.InspectWorkspace(ctx, InspectWorkspaceInput{})
}

func (s *Service) projectID(ctx context.Context, explicit string) (string, *result.OperationResult) {
	if strings.TrimSpace(explicit) != "" {
		return strings.TrimSpace(explicit), nil
	}
	facts, err := s.inspector.Inspect(ctx, "")
	if err != nil {
		failure := fail("resolve_project", err)
		return "", &failure
	}
	saved, found, err := s.bindings.Get(s.endpoint, facts.Identity)
	if err != nil {
		failure := fail("resolve_project", err)
		return "", &failure
	}
	if !found {
		failure := result.Failure("resolve_project", "PROJECT_NOT_BOUND", "workspace is not bound to a TestForge project", false)
		failure.NextActions = append(failure.NextActions, "Call testforge_bind_project first")
		return "", &failure
	}
	return saved.ProjectID, nil
}

func fail(operation string, err error) result.OperationResult {
	var workspaceErr *workspace.Error
	if errors.As(err, &workspaceErr) {
		return result.Failure(operation, workspaceErr.Code, workspaceErr.Message, false)
	}
	var platformErr *testforge.Error
	if errors.As(err, &platformErr) {
		code := "PLATFORM_INPUT"
		retryable := false
		switch {
		case platformErr.Status == 401 || platformErr.Status == 403:
			code = "PLATFORM_UNAUTHORIZED"
		case platformErr.Status == 404:
			code = "PLATFORM_NOT_FOUND"
		case platformErr.Status == 409:
			code = "PLATFORM_CONFLICT"
		case platformErr.Status >= 500:
			code = "PLATFORM_UNAVAILABLE"
			retryable = true
		}
		output := result.Failure(operation, code, platformErr.Message, retryable)
		output.Error.Status = platformErr.Status
		output.Error.TraceID = platformErr.TraceID
		output.Error.Details = map[string]any{"platformCode": platformErr.Code, "details": platformErr.Details}
		return output
	}
	message := err.Error()
	if strings.Contains(message, "BINDING_STORE_INVALID") {
		return result.Failure(operation, "BINDING_STORE_INVALID", message, false)
	}
	retryable := strings.Contains(strings.ToLower(message), "timeout") || strings.Contains(strings.ToLower(message), "connect")
	code := "CONTRACT_MISMATCH"
	if retryable {
		code = "PLATFORM_UNAVAILABLE"
	}
	return result.Failure(operation, code, message, retryable)
}

func matchingProjects(raw any, repositoryURL, root string) []any {
	projects, ok := raw.([]any)
	if !ok {
		return []any{}
	}
	wanted := map[string]bool{}
	if normalized := normalizeRepository(repositoryURL); normalized != "" {
		wanted[normalized] = true
	}
	if normalized := normalizeRepository(root); normalized != "" {
		wanted[normalized] = true
	}
	result := make([]any, 0)
	for _, project := range projects {
		if wanted[normalizeRepository(stringField(project, "repositoryUrl"))] {
			result = append(result, project)
		}
	}
	sort.SliceStable(result, func(left, right int) bool {
		return stringField(result[left], "name") < stringField(result[right], "name")
	})
	return result
}

func normalizeRepository(value string) string {
	value = strings.TrimSpace(strings.ReplaceAll(value, "\\", "/"))
	if value == "" {
		return ""
	}
	if strings.HasPrefix(value, "git@") {
		value = strings.TrimPrefix(value, "git@")
		value = strings.Replace(value, ":", "/", 1)
	} else if parsed, err := url.Parse(value); err == nil && parsed.Host != "" {
		value = parsed.Host + "/" + strings.TrimPrefix(parsed.Path, "/")
	}
	value = strings.TrimSuffix(value, "/")
	value = strings.TrimSuffix(value, ".git")
	return strings.ToLower(value)
}

func validateCaseYAML(source string, allowMissingScript bool) error {
	if strings.TrimSpace(source) == "" {
		return errors.New("Case YAML must not be empty")
	}
	var document map[string]any
	if err := yaml.Unmarshal([]byte(source), &document); err != nil {
		return fmt.Errorf("Case YAML is invalid: %w", err)
	}
	if stringValue(document["apiVersion"]) == "" || stringValue(document["kind"]) != "TestCase" {
		return errors.New("Case YAML requires apiVersion and kind: TestCase")
	}
	metadata, ok := document["metadata"].(map[string]any)
	if !ok || strings.TrimSpace(stringValue(metadata["name"])) == "" {
		return errors.New("Case YAML requires metadata.name")
	}
	spec, ok := document["spec"].(map[string]any)
	if !ok {
		return errors.New("Case YAML requires spec")
	}
	if _, ok := spec["script"].(map[string]any); !ok && !allowMissingScript {
		return errors.New("Case YAML requires exactly one spec.script; provide inline script, an asset reference, or scriptPath")
	}
	return nil
}

func injectAsset(source string, asset any) (string, error) {
	uri := stringField(asset, "uri")
	entrypoints, _ := mapField(asset)["entrypoints"].([]any)
	if uri == "" || len(entrypoints) != 1 || stringValue(entrypoints[0]) == "" {
		return "", errors.New("uploaded Asset response must contain uri and exactly one entrypoint")
	}
	var document map[string]any
	if err := yaml.Unmarshal([]byte(source), &document); err != nil {
		return "", err
	}
	spec, ok := document["spec"].(map[string]any)
	if !ok {
		return "", errors.New("Case YAML requires spec")
	}
	spec["script"] = map[string]any{"type": "asset", "asset": uri, "entrypoint": stringValue(entrypoints[0])}
	encoded, err := yaml.Marshal(document)
	if err != nil {
		return "", fmt.Errorf("encode Case YAML: %w", err)
	}
	return string(encoded), nil
}

func validateWorkflowGraph(graph map[string]any) error {
	if graph == nil {
		return errors.New("graph is required")
	}
	nodes, ok := graph["nodes"].([]any)
	if !ok || len(nodes) == 0 {
		return errors.New("graph.nodes must contain at least one node")
	}
	edges, ok := graph["edges"].([]any)
	if !ok {
		return errors.New("graph.edges must be an array")
	}
	ids := map[string]bool{}
	indegree := map[string]int{}
	adjacency := map[string][]string{}
	for _, rawNode := range nodes {
		id := stringField(rawNode, "id")
		if id == "" {
			return errors.New("every Workflow node requires id")
		}
		if ids[id] {
			return fmt.Errorf("duplicate Workflow node id %s", id)
		}
		ids[id] = true
		indegree[id] = 0
	}
	seenEdges := map[string]bool{}
	for _, rawEdge := range edges {
		from := stringField(rawEdge, "predecessorNodeId")
		to := stringField(rawEdge, "successorNodeId")
		if !ids[from] || !ids[to] {
			return errors.New("Workflow edge references an unknown node")
		}
		if from == to {
			return errors.New("Workflow self-dependency is not allowed")
		}
		key := from + "\x00" + to
		if seenEdges[key] {
			return errors.New("duplicate Workflow edge is not allowed")
		}
		seenEdges[key] = true
		adjacency[from] = append(adjacency[from], to)
		indegree[to]++
	}
	queue := make([]string, 0)
	for id, degree := range indegree {
		if degree == 0 {
			queue = append(queue, id)
		}
	}
	visited := 0
	for len(queue) > 0 {
		id := queue[0]
		queue = queue[1:]
		visited++
		for _, successor := range adjacency[id] {
			indegree[successor]--
			if indegree[successor] == 0 {
				queue = append(queue, successor)
			}
		}
	}
	if visited != len(ids) {
		return errors.New("Workflow graph must be acyclic")
	}
	return nil
}

func requestKeyOrNew(value string) (string, error) {
	value = strings.ToLower(strings.TrimSpace(value))
	if value == "" {
		bytes := make([]byte, 16)
		if _, err := rand.Read(bytes); err != nil {
			return "", fmt.Errorf("generate requestKey: %w", err)
		}
		bytes[6] = (bytes[6] & 0x0f) | 0x40
		bytes[8] = (bytes[8] & 0x3f) | 0x80
		hexValue := hex.EncodeToString(bytes)
		return fmt.Sprintf("%s-%s-%s-%s-%s", hexValue[0:8], hexValue[8:12], hexValue[12:16], hexValue[16:20], hexValue[20:32]), nil
	}
	if !regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`).MatchString(value) {
		return "", errors.New("requestKey must be a valid UUID")
	}
	return value, nil
}

func mapField(value any) map[string]any {
	if mapped, ok := value.(map[string]any); ok {
		return mapped
	}
	return map[string]any{}
}

func stringField(value any, name string) string { return stringValue(mapField(value)[name]) }

func stringValue(value any) string {
	if text, ok := value.(string); ok {
		return strings.TrimSpace(text)
	}
	return ""
}

func intField(value any, name string) (int, bool) {
	switch number := mapField(value)[name].(type) {
	case json.Number:
		parsed, err := number.Int64()
		return int(parsed), err == nil
	case float64:
		return int(number), true
	case int:
		return number, true
	case int64:
		return int(number), true
	default:
		return 0, false
	}
}

func firstTargetID(project any) string {
	targets, _ := mapField(project)["targets"].([]any)
	if len(targets) == 0 {
		return ""
	}
	return stringField(targets[0], "id")
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}
