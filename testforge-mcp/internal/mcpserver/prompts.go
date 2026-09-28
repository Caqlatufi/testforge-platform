package mcpserver

import (
	"context"
	"fmt"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

func registerPrompts(server *mcp.Server) {
	server.AddPrompt(&mcp.Prompt{
		Name: "test_current_project", Title: "Test current project",
		Description: "Inspect, bind, prepare assets, execute a fixed Commit and return a deterministic TestForge report.",
	}, func(_ context.Context, _ *mcp.GetPromptRequest) (*mcp.GetPromptResult, error) {
		return prompt(`Use TestForge to test the currently authorized local Git project.
1. Inspect the workspace and state which exact Commit can be tested.
2. Bind an existing project or ask before creating one when the choice is ambiguous.
3. Reuse valid Cases and the latest published project Workflow where possible.
4. Never claim dirty, uncommitted changes were tested.
5. Preserve requestKey for every uncertain publish or execution retry.
6. Return deterministic Report facts and Evidence separately from AI diagnosis suggestions.`), nil
	})
	server.AddPrompt(&mcp.Prompt{
		Name: "add_regression_case", Title: "Add regression Case",
		Description: "Create or update one YAML-first regression Case and place it in a project Workflow.",
		Arguments:   []*mcp.PromptArgument{{Name: "requirement", Description: "Requirement, defect or behavior to cover", Required: true}},
	}, func(_ context.Context, request *mcp.GetPromptRequest) (*mcp.GetPromptResult, error) {
		value := request.Params.Arguments["requirement"]
		return prompt(fmt.Sprintf(`Add a TestForge regression Case for the following behavior:
%s

Inspect the workspace first. Generate one complete YAML-first Case with exactly one inline script or Case-bound uploaded Asset. Validate before applying it, update the project Workflow DAG, publish with a stable requestKey, and summarize the changed TestForge object IDs.`, value)), nil
	})
	server.AddPrompt(&mcp.Prompt{
		Name: "analyze_test_failure", Title: "Analyze TestForge failure",
		Description: "Analyze a failed Run using deterministic report facts and Evidence.",
		Arguments:   []*mcp.PromptArgument{{Name: "runId", Description: "TestForge Run UUID", Required: true}},
	}, func(_ context.Context, request *mcp.GetPromptRequest) (*mcp.GetPromptResult, error) {
		return prompt(fmt.Sprintf(`Read testforge://runs/%s/report. Separate deterministic status, failed Cases, timing and Evidence from hypotheses. Cite Evidence URIs for each conclusion. Do not change the TestForge result and do not describe AI diagnosis as test truth.`, request.Params.Arguments["runId"])), nil
	})
}

func prompt(text string) *mcp.GetPromptResult {
	return &mcp.GetPromptResult{
		Description: "TestForge MCP workflow guidance",
		Messages:    []*mcp.PromptMessage{{Role: mcp.Role("user"), Content: &mcp.TextContent{Text: text}}},
	}
}
