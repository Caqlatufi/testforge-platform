package integration

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

func TestRealStdioServer(t *testing.T) {
	binary := os.Getenv("TESTFORGE_MCP_BINARY")
	workspace := os.Getenv("TESTFORGE_MCP_E2E_WORKSPACE")
	baseURL := os.Getenv("TESTFORGE_MCP_E2E_URL")
	if binary == "" || workspace == "" || baseURL == "" {
		t.Skip("set TESTFORGE_MCP_BINARY, TESTFORGE_MCP_E2E_WORKSPACE and TESTFORGE_MCP_E2E_URL")
	}
	absoluteBinary, err := filepath.Abs(binary)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, absoluteBinary,
		"--url", baseURL,
		"--workspace", workspace,
		"--bindings", filepath.Join(t.TempDir(), "bindings.json"),
	)
	client := mcp.NewClient(&mcp.Implementation{Name: "testforge-mcp-e2e", Version: "test"}, nil)
	session, err := client.Connect(ctx, &mcp.CommandTransport{Command: command}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	tools, err := session.ListTools(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(tools.Tools) != 9 {
		t.Fatalf("expected 9 tools, got %d", len(tools.Tools))
	}
	result, err := session.CallTool(ctx, &mcp.CallToolParams{Name: "testforge_inspect_workspace", Arguments: map[string]any{}})
	if err != nil {
		t.Fatal(err)
	}
	if result.IsError || result.StructuredContent == nil {
		t.Fatalf("inspect failed: %#v", result)
	}
}
