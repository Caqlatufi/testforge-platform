package mcpserver

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"github.com/caqlatufi/testforge-mcp/internal/application"
	"github.com/caqlatufi/testforge-mcp/internal/binding"
	"github.com/caqlatufi/testforge-mcp/internal/config"
	"github.com/caqlatufi/testforge-mcp/internal/testforge"
	"github.com/caqlatufi/testforge-mcp/internal/workspace"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

func TestServerDiscoversCapabilitiesAndCallsInspectTool(t *testing.T) {
	repository := t.TempDir()
	git(t, repository, "init")
	git(t, repository, "config", "user.email", "test@example.com")
	git(t, repository, "config", "user.name", "Test")
	if err := os.WriteFile(filepath.Join(repository, "README.md"), []byte("test"), 0o644); err != nil {
		t.Fatal(err)
	}
	git(t, repository, "add", ".")
	git(t, repository, "commit", "-m", "initial")
	apiServer := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/api/v1/projects" {
			t.Fatalf("unexpected API request %s", request.URL.Path)
		}
		response.Header().Set("Content-Type", "application/json")
		io.WriteString(response, `{"code":"OK","message":"ok","traceId":"0123456789abcdef0123456789abcdef","data":[]}`)
	}))
	defer apiServer.Close()
	inspector, err := workspace.NewInspector(repository)
	if err != nil {
		t.Fatal(err)
	}
	service := application.New(apiServer.URL, testforge.NewClient(apiServer.URL, "", 0), inspector, binding.NewStore(filepath.Join(t.TempDir(), "bindings.json")))
	server := New(service, config.BuildInfo{Version: "test", Protocol: config.DefaultProtocol})
	serverTransport, clientTransport := mcp.NewInMemoryTransports()
	ctx := context.Background()
	if _, err := server.Connect(ctx, serverTransport, nil); err != nil {
		t.Fatal(err)
	}
	client := mcp.NewClient(&mcp.Implementation{Name: "test-client", Version: "test"}, nil)
	session, err := client.Connect(ctx, clientTransport, nil)
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
	prompts, err := session.ListPrompts(ctx, nil)
	if err != nil || len(prompts.Prompts) != 3 {
		t.Fatalf("unexpected prompts: %#v %v", prompts, err)
	}
	resources, err := session.ListResources(ctx, nil)
	if err != nil || len(resources.Resources) != 2 {
		t.Fatalf("unexpected resources: %#v %v", resources, err)
	}
	call, err := session.CallTool(ctx, &mcp.CallToolParams{Name: "testforge_inspect_workspace", Arguments: map[string]any{}})
	if err != nil {
		t.Fatal(err)
	}
	if call.IsError || call.StructuredContent == nil {
		t.Fatalf("unexpected tool result: %#v", call)
	}
	resource, err := session.ReadResource(ctx, &mcp.ReadResourceParams{URI: "testforge://workspace/context"})
	if err != nil || len(resource.Contents) != 1 {
		t.Fatalf("unexpected resource: %#v %v", resource, err)
	}
}

func git(t *testing.T, directory string, args ...string) {
	t.Helper()
	commandArgs := append([]string{"-C", directory}, args...)
	cmd := exec.Command("git", commandArgs...)
	cmd.Env = append(os.Environ(), "GIT_CONFIG_NOSYSTEM=1")
	if output, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("git %v failed: %v\n%s", args, err, output)
	}
}
