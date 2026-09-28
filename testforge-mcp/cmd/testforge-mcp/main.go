package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"

	"github.com/caqlatufi/testforge-mcp/internal/application"
	"github.com/caqlatufi/testforge-mcp/internal/binding"
	"github.com/caqlatufi/testforge-mcp/internal/config"
	"github.com/caqlatufi/testforge-mcp/internal/mcpserver"
	"github.com/caqlatufi/testforge-mcp/internal/testforge"
	"github.com/caqlatufi/testforge-mcp/internal/workspace"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

var (
	version   = "0.1.0-dev"
	commit    = "unknown"
	buildDate = "unknown"
)

func main() {
	build := config.BuildInfo{Version: version, Commit: commit, BuildDate: buildDate, Protocol: config.DefaultProtocol}
	if len(os.Args) == 2 && os.Args[1] == "version" {
		if err := json.NewEncoder(os.Stdout).Encode(build); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	cfg, err := config.Load(os.Args[1:], build)
	if err != nil {
		fmt.Fprintln(os.Stderr, "testforge-mcp configuration error:", err)
		os.Exit(2)
	}
	inspector, err := workspace.NewInspector(cfg.Workspace)
	if err != nil {
		fmt.Fprintln(os.Stderr, "testforge-mcp workspace error:", err)
		os.Exit(2)
	}
	api := testforge.NewClient(cfg.BaseURL, cfg.Token, cfg.Timeout)
	service := application.New(cfg.BaseURL, api, inspector, binding.NewStore(cfg.BindingFile))
	server := mcpserver.New(service, cfg.Build)
	logger := log.New(os.Stderr, "testforge-mcp: ", log.LstdFlags|log.LUTC)
	if err := server.Run(context.Background(), &mcp.StdioTransport{}); err != nil {
		logger.Printf("server stopped: %v", err)
		os.Exit(1)
	}
}
